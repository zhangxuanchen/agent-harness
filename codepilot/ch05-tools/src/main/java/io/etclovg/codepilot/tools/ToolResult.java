package io.etclovg.codepilot.tools;

/**
 * T 层 · 工具调用结果统一密封接口。
 * <p>对应书中 Ch05 §5.5.4 — 结构化错误协议。
 * <p>将工具执行结果统一为两种类型：成功（{@link ToolSuccessResult}）或失败（{@link ToolErrorResult}），
 * 取代传统的异常抛出模式。模型收到结果后可直接读取结构化字段来理解"为什么失败"和"怎么恢复"。
 *
 * @see ToolSuccessResult
 * @see ToolErrorResult
 */
public sealed interface ToolResult permits ToolSuccessResult, ToolErrorResult {

    /** 工具名称 */
    String toolName();

    /** 是否执行成功 */
    default boolean isSuccess() {
        return this instanceof ToolSuccessResult;
    }

    /** 转换为模型可读的摘要文本，供回填到对话上下文 */
    default String toSummary() {
        return switch (this) {
            case ToolSuccessResult r -> String.format(
                    "[工具 %s 执行成功] 耗时 %dms", r.toolName(), r.durationMs());
            case ToolErrorResult r -> String.format(
                    "[工具 %s 执行失败] %s: %s", r.toolName(), r.errorCode(), r.errorMessage());
        };
    }
}