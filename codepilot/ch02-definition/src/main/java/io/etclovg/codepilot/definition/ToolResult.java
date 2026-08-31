package io.etclovg.codepilot.definition;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 工具执行结果记录。
 * <p>对应书中 Ch02 §2.4 —— 工具调用接口的标准返回结构。
 * <p>统一描述一次工具调用的产出，包括输出内容、是否成功、耗时与附加元数据，
 * 供验证中间件与编排层据此决策后续动作（重试、回滚或继续）。
 *
 * @param toolName    执行的工具名称
 * @param success     是否执行成功
 * @param output      工具输出内容（成功时为结果文本，失败时为错误描述）
 * @param errorCode   错误代码，成功时为 {@code null}
 * @param startedAt   执行开始时间
 * @param duration    执行耗时
 * @param metadata    附加元数据（如调用参数摘要、上游请求 ID 等）
 */
public record ToolResult(
        String toolName,
        boolean success,
        String output,
        String errorCode,
        Instant startedAt,
        Duration duration,
        Map<String, Object> metadata
) {

    /**
     * 构造成功结果。
     *
     * @param toolName 工具名称
     * @param output   输出内容
     * @param duration 执行耗时
     * @return 成功结果实例
     */
    public static ToolResult success(String toolName, String output, Duration duration) {
        return new ToolResult(toolName, true, output, null, Instant.now(), duration, Map.of());
    }

    /**
     * 构造失败结果。
     *
     * @param toolName  工具名称
     * @param errorCode 错误代码
     * @param message   错误信息
     * @param duration  执行耗时
     * @return 失败结果实例
     */
    public static ToolResult failure(String toolName, String errorCode, String message, Duration duration) {
        return new ToolResult(toolName, false, message, errorCode, Instant.now(), duration, Map.of());
    }

    /**
     * 判断是否可重试。
     * <p>占位实现：仅对超时类错误码视为可重试。
     *
     * @return 可重试返回 true
     */
    public boolean isRetryable() {
        return !success && "TIMEOUT".equalsIgnoreCase(errorCode);
    }
}
