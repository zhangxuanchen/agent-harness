package io.etclovg.codepilot.tools;

import java.util.Map;

/**
 * 工具错误结果。
 * <p>对应书中 Ch05 §5.2 —— 工具执行失败后返回的标准结果结构。
 *
 * @param toolName   工具名称
 * @param errorCode  错误代码
 * @param errorMessage 错误信息
 * @param retryable  是否可重试
 * @param metadata   附加元数据
 */
public record ToolErrorResult(
        String toolName,
        String errorCode,
        String errorMessage,
        boolean retryable,
        Map<String, Object> metadata
) implements ToolResult {
    public static ToolErrorResult of(String toolName, String errorCode, String message) {
        return new ToolErrorResult(toolName, errorCode, message, true, Map.of());
    }
}