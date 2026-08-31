package io.etclovg.codepilot.reliability;

/**
 * 节点类型枚举：定义 Agent 工作流中的五种核心节点类型
 * 对应书中 Ch17 — 生产监控可靠性与成本中的 SLI 差异化策略
 *
 * <p>每种节点类型有不同的性能特征和可靠性要求：
 * <ul>
 *   <li>LLM_INFERENCE: 大模型推理节点，延迟高、成本高、可靠性关键</li>
 *   <li>TOOL_INVOCATION: 工具调用节点，延迟中等、需要超时控制</li>
 *   <li>RETRIEVAL: 检索节点，延迟波动大、依赖外部系统</li>
 *   <li>VALIDATION: 验证节点，延迟低、需要高准确性</li>
 *   <li>COMPRESSION: 压缩节点，延迟低、资源消耗中等</li>
 * </ul>
 */
public enum NodeType {
    /** LLM 推理节点：执行大模型推理调用 */
    LLM_INFERENCE("LLM推理", true, true),

    /** 工具调用节点：执行外部工具/API 调用 */
    TOOL_INVOCATION("工具调用", false, true),

    /** 检索节点：执行向量检索或知识库查询 */
    RETRIEVAL("检索", false, true),

    /** 验证节点：执行结果验证和质控 */
    VALIDATION("验证", false, true),

    /** 压缩节点：执行上下文压缩和摘要 */
    COMPRESSION("压缩", false, false);

    private final String displayName;
    private final boolean highCost;
    private final boolean latencySensitive;

    NodeType(String displayName, boolean highCost, boolean latencySensitive) {
        this.displayName = displayName;
        this.highCost = highCost;
        this.latencySensitive = latencySensitive;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isHighCost() {
        return highCost;
    }

    public boolean isLatencySensitive() {
        return latencySensitive;
    }
}