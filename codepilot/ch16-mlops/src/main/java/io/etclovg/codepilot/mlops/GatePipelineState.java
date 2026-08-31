package io.etclovg.codepilot.mlops;

import java.util.List;

/**
 * 门禁流水线执行状态（教学示意）。对应书中 Ch16 §16.1.1。
 * <p>累积四阶段门禁结果 + 阻断位置。生产实现见
 * {@link GatePipelineOrchestrator}（含 {@code PipelineExecution} 与阶段处理器映射），
 * 本类是其教学简化版，用于讲清"短路阻断 + 四态判定"逻辑。
 *
 * @param gates     各门禁结果（按执行顺序）
 * @param blockedAt 阻断门禁名；null 表示全部通过
 */
public record GatePipelineState(List<GateResult> gates, String blockedAt) {

    /** 在指定门禁阻断。 */
    public static GatePipelineState blocked(String at, List<GateResult> gates) {
        return new GatePipelineState(List.copyOf(gates), at);
    }

    /** 全部门禁通过。 */
    public static GatePipelineState passed(List<GateResult> gates) {
        return new GatePipelineState(List.copyOf(gates), null);
    }

    /** 是否被阻断。 */
    public boolean isBlocked() {
        return blockedAt != null;
    }
}
