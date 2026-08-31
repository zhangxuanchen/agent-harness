package io.etclovg.codepilot.data;

import java.time.Instant;

/**
 * 错误样本记录。
 * <p>对应书中 Ch13 §13.3 —— 数据飞轮 O 层采集的结构化错误案例。
 * <p>由 {@link ErrorCollector#collect} 在时间窗口内聚合 V 层验证失败
 * 与用户负面反馈，结构化为 (input, expected, actual, error_type)，
 * 供数据清洗与模型微调使用。
 *
 * @param input     原始输入
 * @param expected  期望输出
 * @param actual    实际输出
 * @param errorType 错误类型（如 misrouting / rewrite_error）
 * @param timestamp 发生时间
 */
public record ErrorSample(
        String input,
        String expected,
        String actual,
        String errorType,
        Instant timestamp
) {
}
