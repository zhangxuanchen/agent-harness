package io.etclovg.codepilot.tools;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * G 层 · 工具策略中间件。
 * <p>在每次工具调用前执行运行时权限检查——白名单、频率限制、每小时配额、人工审批。
 * 对应书中 Ch5 §5.7.1 — 工具的运行时权限管理。
 *
 * <p><b>安全模型</b>：default-deny——未明确授权的工具默认不可用<br>
 * <b>效果</b>：高风险工具误调用率从 3.1% 降至 0.25%（-92%）[^10]<br>
 * <b>故障恢复</b>：从人工发现（平均 47 分钟）缩短到自动拦截（&lt; 1 秒）
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onActing} 阶段，
 * 拦截非法工具调用时通过 {@link RuntimeContext} 打标并短路返回空事件流。
 */
@Component
public class ToolPolicyAdvisor extends AbstractLayerMiddleware {

    /** 工具名 → 调用计数器（每小时） */
    private final Map<String, AtomicInteger> hourlyCounters = new ConcurrentHashMap<>();

    /** 工具名 → 上次调用时间（秒级限流） */
    private final Map<String, Long> lastCallTimestamps = new ConcurrentHashMap<>();

    /** 当前用户角色（生产环境从 SecurityContext 获取） */
    private String currentUserRole = "user";

    /** 当前环境 */
    private String currentEnvironment = "dev";

    public ToolPolicyAdvisor() {
        super(Layer.G, "ToolPolicyAdvisor-G");
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        Map<String, Object> context = rc.getExtra();
        String toolName = context.getOrDefault("tool.name", "unknown").toString();

        // 尝试获取工具策略元数据
        ToolPolicy policy = context.getOrDefault("tool.policy", null) instanceof ToolPolicy tp
                ? tp : null;

        // —— 默认拒绝检查 ——
        if (policy == null) {
            log.warn("[G层策略] 工具 \"{}\" 未声明 ToolPolicy——default-deny：已阻止", toolName);
            return policyBlocked(rc, toolName, "该工具未声明访问策略——默认拒绝", "POLICY_NOT_DECLARED");
        }

        // —— 环境检查 ——
        if (policy.allowedEnvironments().length > 0) {
            boolean envAllowed = Arrays.asList(policy.allowedEnvironments()).contains(currentEnvironment);
            if (!envAllowed) {
                log.warn("[G层策略] 工具 \"{}\" 不允许在环境 \"{}\" 中执行——已阻止", toolName, currentEnvironment);
                return policyBlocked(rc, toolName,
                        "工具 \"" + toolName + "\" 不允许在环境 \"" + currentEnvironment + "\" 中执行",
                        "ENV_NOT_ALLOWED");
            }
        }

        // —— 角色检查 ——
        if (policy.allowedRoles().length > 0) {
            boolean roleAllowed = Arrays.asList(policy.allowedRoles()).contains(currentUserRole);
            if (!roleAllowed) {
                log.warn("[G层策略] 角色 \"{}\" 无权调用工具 \"{}\"——已阻止",
                        currentUserRole, toolName);
                return policyBlocked(rc, toolName,
                        "角色 \"" + currentUserRole + "\" 无权调用工具 \"" + toolName
                                + "\"——需要角色: " + Arrays.toString(policy.allowedRoles()),
                        "ROLE_NOT_ALLOWED");
            }
        }

        // —— 每秒限流检查（令牌桶简化版） ——
        if (policy.rateLimit() < Integer.MAX_VALUE) {
            long now = System.currentTimeMillis();
            long lastCall = lastCallTimestamps.getOrDefault(toolName, 0L);
            long minIntervalMs = 1000L / policy.rateLimit();

            if (now - lastCall < minIntervalMs) {
                log.warn("[G层策略] 工具 \"{}\" 达到每秒限流({})——已阻止", toolName, policy.rateLimit());
                return policyBlocked(rc, toolName,
                        "工具 \"" + toolName + "\" 达到每秒限流——请等待 "
                                + (minIntervalMs - (now - lastCall)) + "ms 后重试",
                        "RATE_LIMIT_EXCEEDED");
            }
            lastCallTimestamps.put(toolName, now);
        }

        // —— 每小时配额检查 ——
        if (policy.maxCallsPerHour() < Integer.MAX_VALUE) {
            AtomicInteger counter = hourlyCounters.computeIfAbsent(toolName,
                    k -> new AtomicInteger(0));
            if (counter.incrementAndGet() > policy.maxCallsPerHour()) {
                log.warn("[G层策略] 工具 \"{}\" 达到每小时配额({})——已阻止",
                        toolName, policy.maxCallsPerHour());
                return policyBlocked(rc, toolName,
                        "工具 \"" + toolName + "\" 达到每小时配额(" + policy.maxCallsPerHour() + ")——请一小时后再试",
                        "HOURLY_QUOTA_EXCEEDED");
            }
        }

        // —— 人工审批检查 ——
        if (policy.requireHumanApproval()) {
            boolean approved = Boolean.parseBoolean(
                    context.getOrDefault("tool.human_approved", "false").toString());
            if (!approved) {
                log.warn("[G层策略] 工具 \"{}\" 需要人工审批——已阻止", toolName);
                return policyBlocked(rc, toolName,
                        "工具 \"" + toolName + "\" 需要人工审批——请确认操作后重试",
                        "HUMAN_APPROVAL_REQUIRED");
            }
        }

        log.debug("[G层策略] 工具 \"{}\" 通过所有运行时策略检查 ✓", toolName);
        return next.apply(input);
    }

    /**
     * 策略阻止——将结构化错误写入 {@link RuntimeContext} 并返回空事件流短路。
     */
    private Flux<AgentEvent> policyBlocked(RuntimeContext rc, String toolName, String detail, String errorCode) {
        rc.put("tool.policy.blocked", Map.of(
                "blocked", true,
                "tool", toolName,
                "error", errorCode,
                "detail", detail,
                "suggestion", "请尝试不同的操作或联系管理员申请权限",
                "retryable", false
        ));
        return Flux.empty();
    }

    /**
     * 重置每小时计数器（通常由 @Scheduled 定时任务调用）。
     */
    public void resetHourlyCounters() {
        hourlyCounters.clear();
        log.debug("[G层策略] 每小时调用计数器已重置");
    }

    // —— 配置方法 ——
    public void setCurrentUserRole(String role) {
        this.currentUserRole = role;
    }

    public void setCurrentEnvironment(String env) {
        this.currentEnvironment = env;
    }
}
