package io.etclovg.codepilot.future;

/**
 * 模型能力枚举。
 * <p>对应书中 Ch19 §19.3 —— 模型能力的结构化描述。
 * <p>用于在模型路由与自我改进中声明模型具备的能力维度，
 * 供能力评估与路由决策参考。
 * <ul>
 *   <li>{@link #CODE_GENERATION}   代码生成</li>
 *   <li>{@link #REASONING}         推理</li>
 *   <li>{@link #TOOL_USE}          工具调用</li>
 *   <li>{@link #MULTIMODAL}        多模态</li>
 *   <li>{@link #LONG_CONTEXT}      长上下文</li>
 *   <li>{@link #AGENTS}            Agent 编排</li>
 * </ul>
 */
public enum ModelCapability {

    /** 代码生成 */
    CODE_GENERATION("代码生成"),
    /** 推理 */
    REASONING("推理"),
    /** 工具调用 */
    TOOL_USE("工具调用"),
    /** 多模态 */
    MULTIMODAL("多模态"),
    /** 长上下文 */
    LONG_CONTEXT("长上下文"),
    /** Agent 编排 */
    AGENTS("Agent 编排");

    private final String label;

    ModelCapability(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
