package io.etclovg.codepilot.data;

import io.agentscope.core.ReActAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 工具制造流水线。
 * <p>对应书中 Ch13 §13.2 —— 五阶段工具制造安全管线（O→Agent→V→G→T）。
 * <p>串联 O 层发现（{@link OperationTracker}）→ Agent 生成（{@link ReActAgent}）
 * → V 层验证（{@link SafetyValidator}）→ G 层审批（{@link ApprovalGateway}）
 * → T 层注册（{@link ToolRegistry}）五个阶段，每个阶段是单向门禁——
 * 通过则进入下一阶段，失败则回退到 Agent 生成重试。
 */
@Component
public class ToolManufacturingPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolManufacturingPipeline.class);

    /** O 层：高频操作模式发现 */
    private final OperationTracker tracker;
    /** Agent 生成：LLM 自动生成实现草稿 */
    private final ReActAgent agent;
    /** V 层：安全+功能+性能三项门禁 */
    private final SafetyValidator validator;
    /** G 层：权限模型审计+合规检查 */
    private final ApprovalGateway gateway;
    /** T 层：工具组动态注册 */
    private final ToolRegistry registry;

    /**
     * 构造函数注入五阶段依赖。
     *
     * @param tracker   O 层操作追踪器
     * @param agent     Agent 生成器
     * @param validator V 层安全校验器
     * @param gateway   G 层审批网关
     * @param registry  T 层工具注册中心
     */
    public ToolManufacturingPipeline(OperationTracker tracker,
                                     ReActAgent agent,
                                     SafetyValidator validator,
                                     ApprovalGateway gateway,
                                     ToolRegistry registry) {
        this.tracker = tracker;
        this.agent = agent;
        this.validator = validator;
        this.gateway = gateway;
        this.registry = registry;
    }

    /**
     * 发现并制造工具候选。
     * <p>对 {@code operationDomain} 域追踪高频操作模式，逐个交由 Agent 生成实现草稿，
     * 经 V 层验证、G 层审批后注册到 T 层。验证/审批失败则标记阶段并跳过。
     *
     * @param operationDomain 操作域
     * @return 桩实现返回 null（无模式或全部处理完毕）
     */
    public ToolCandidate discoverAndManufacture(String operationDomain) {
        // O 层：追踪高频人工操作 → 发现可工具化候选
        List<OperationPattern> patterns = tracker.findFrequent(operationDomain, 7, 50);
        for (OperationPattern pattern : patterns) {
            // Agent 生成：LLM 自动生成工具实现草稿
            // 概念示例：实际应调用 agent.call().user(...).execute().entity(ToolCandidate.class)，
            // 此处简化为桩候选。本桩实现中 findFrequent 返回空列表，循环体不会执行。
            ToolCandidate candidate = ToolCandidate.of(pattern.operation(), "基于模式生成的工具");

            // V 层：三合一验证门禁
            ValidationResult vr = validator.validate(candidate);
            if (!vr.passed()) {
                candidate.setStatus(Stage.REJECTED_AT_V);
                continue;  // 验证失败 → 回退，不进入后续阶段
            }
            // G 层：审批
            if (gateway.approve(candidate)) {
                registry.register(candidate);  // T 层：注册
                candidate.setStatus(Stage.REGISTERED);
            }
        }
        return null;
    }

    /**
     * 制造结果（保留原有内部记录）。
     */
    public record ManufacturingResult(
            String toolId,
            String toolName,
            boolean success,
            String message
    ) {
    }

    /**
     * 执行制造流水线（保留原有便捷方法）。
     */
    public ManufacturingResult manufacture(String dataSource, String toolSpec) {
        log.info("[ToolManufacturing] 开始制造: source={}", dataSource);
        return new ManufacturingResult(
                "tool-" + UUID.randomUUID().toString().substring(0, 8),
                toolSpec, true, "Manufacturing completed"
        );
    }
}
