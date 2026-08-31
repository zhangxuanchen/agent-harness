package io.etclovg.codepilot.observability;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.function.Function;

/**
 * G 层 · 安全守护总入口。
 * <p>对应书中 Ch08 §8.1（Harness 假设声明）+ Ch11 §11.3（G 层总入口装配）。
 *
 * <p>一个 Middleware 内部覆盖三检查点，注册在链首即可同时满足
 * "输入最先拦、输出最后拦、工具调用时拦"：
 * <ul>
 *   <li>{@code onAgent} 入站——输入检查：防提示注入 / 越狱拦截（L1 正则级）</li>
 *   <li>{@code doOnNext} 出站——输出检查：SQL 注入 / PII 脱敏标记</li>
 *   <li>{@code onActing} 工具调用——工具白名单检查：未注册工具 = 拒绝</li>
 * </ul>
 *
 * <p>本章是 Ch08 Harness 假设声明的载体，也是 Ch11 G 层总入口的工程落点。
 * 真实生产中三检查点的详细逻辑由 ch10-governance 的 InputGuardAdvisor /
 * OutputGuardAdvisor / ToolGuardAdvisor 承载，本类作为总入口委托调用。
 */
@Component("safeGuard")
public class SafeGuardMiddleware extends AbstractLayerMiddleware {

    /** 输入检查——提示注入特征模式（L1 正则级，< 1ms） */
    private static final Set<String> INJECTION_PATTERNS = Set.of(
            "ignore previous instructions", "ignore all previous",
            "dan mode", "jailbreak", "system prompt:", "you are now");

    /** 工具白名单——未注册工具按最小权限原则拒绝 */
    private static final Set<String> TOOL_WHITELIST = Set.of(
            "queryOrder", "checkRefund", "executeRefund", "sendEmail",
            "createManualTicket", "search", "calculate", "fetch", "write", "verify");

    /** PII 特征——手机号（11 位）/ 银行卡号（16-19 位） */
    private static final String PII_PHONE = "\\b\\d{11}\\b";
    private static final String PII_BANK_CARD = "\\b\\d{16,19}\\b";

    private int interceptCount = 0;
    private int totalCalls = 0;

    public SafeGuardMiddleware() {
        super(Layer.G, "SafeGuard");
    }

    /**
     * Harness 假设声明——G 层总入口三检查点：输入拦截 + 输出过滤 + 工具白名单。
     */
    @HarnessAssumption(
        statement = "G 层总入口：输入防注入 + 输出过滤 SQL/PII + 工具白名单",
        expectedRange = "2-5% 的总响应中触发输出拦截",
        registeredAt = "2026-03-15"
    )
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        totalCalls++;

        // ── 检查点 1: INPUT —— 入站做输入检查（防提示注入，< 1ms） ──
        String userInput = rc.getExtra().getOrDefault("user.input", "").toString();
        if (!userInput.isBlank() && containsInjection(userInput)) {
            interceptCount++;
            log.warn("[SafeGuard] 输入检查拦截：检测到提示注入模式");
            rc.put("guard.blocked", true);
            rc.put("guard.reason", "L1_PROMPT_INJECTION");
            return Flux.empty();  // 终止——不让危险进入
        }

        // ── 检查点 3: OUTPUT —— 出站做输出检查（SQL 注入 + PII，doOnNext） ──
        return next.apply(input)
            .doOnNext(event -> {
                String content = extractContent(event);
                if (content != null) {
                    if (containsSqlInjection(content)) {
                        interceptCount++;
                        log.warn("[SafeGuard] 输出检查拦截：检测到 SQL 注入模式");
                        rc.put("guard.blocked", true);
                        rc.put("guard.reason", "SQL_INJECTION");
                    } else if (containsPii(content)) {
                        interceptCount++;
                        log.warn("[SafeGuard] 输出检查拦截：检测到 PII 泄露");
                        rc.put("guard.blocked", true);
                        rc.put("guard.reason", "PII_LEAK");
                    }
                }
            });
    }

    /**
     * ── 检查点 2: TOOL —— 工具调用时做白名单检查（onActing） ──
     * 未在白名单中的工具 = 拒绝执行。生产中由 ch10 ToolGuardAdvisor 承载详细策略。
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        String toolName = rc.getExtra().getOrDefault("loop.tool.name", "").toString();
        if (!toolName.isBlank() && !TOOL_WHITELIST.contains(toolName)) {
            interceptCount++;
            log.warn("[SafeGuard] 工具白名单拦截：工具 {} 未注册", toolName);
            rc.put("guard.blocked", true);
            rc.put("guard.reason", "TOOL_NOT_IN_WHITELIST");
            return Flux.empty();
        }
        return next.apply(input);
    }

    // ═══════ 检测方法 ═══════

    private boolean containsInjection(String content) {
        String lower = content.toLowerCase();
        return INJECTION_PATTERNS.stream().anyMatch(lower::contains);
    }

    private boolean containsSqlInjection(String content) {
        String lower = content.toLowerCase();
        return lower.contains("drop table") || lower.contains("delete from") ||
               lower.contains("insert into") || lower.contains("update set") ||
               (lower.contains("--") && lower.contains(" or "));
    }

    private boolean containsPii(String content) {
        return content.matches(".*" + PII_PHONE + ".*") ||
               content.matches(".*" + PII_BANK_CARD + ".*");
    }

    private String extractContent(AgentEvent event) {
        return event != null ? event.toString() : null;
    }

    // ═══════ O 层 Metrics 兼容（拦截率统计） ═══════

    public double getInterceptRate() {
        return totalCalls > 0 ? (double) interceptCount / totalCalls : 0.0;
    }

    public int getInterceptCount() { return interceptCount; }
    public int getTotalCalls() { return totalCalls; }
}

/**
 * Harness 假设声明注解。
 * <p>用于标注 Middleware/沙箱/评估器组件的隐含假设。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface HarnessAssumption {
    String statement();
    String expectedRange();
    String registeredAt();
}
