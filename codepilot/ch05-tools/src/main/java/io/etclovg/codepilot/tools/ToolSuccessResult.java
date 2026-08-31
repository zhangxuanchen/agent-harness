package io.etclovg.codepilot.tools;

import java.util.Map;

/**
 * 工具成功执行结果。
 * <p>对应书中 Ch05 §5.2 —— 工具成功执行后返回的标准结果结构。
 *
 * @param toolName    工具名称
 * @param result      执行结果
 * @param durationMs  耗时
 * @param metadata    附加元数据
 */
public record ToolSuccessResult(
        String toolName,
        Object result,
        long durationMs,
        Map<String, Object> metadata
) implements ToolResult {
    public static ToolSuccessResult of(String toolName, Object result) {
        return new ToolSuccessResult(toolName, result, 0, Map.of());
    }
}