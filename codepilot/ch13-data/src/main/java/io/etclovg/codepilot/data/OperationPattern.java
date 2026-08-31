package io.etclovg.codepilot.data;

/**
 * 操作模式记录。
 * <p>对应书中 Ch13 §13.2 —— O 层发现阶段追踪到的高频人工操作模式。
 * <p>当某操作在时间窗口内重复达到阈值时，由 {@link OperationTracker#findFrequent}
 * 产出，作为工具制造的输入候选。
 *
 * @param operation 操作名称/签名
 * @param frequency 时间窗口内出现的频次
 * @param domain    所属业务域
 */
public record OperationPattern(String operation, int frequency, String domain) {
}
