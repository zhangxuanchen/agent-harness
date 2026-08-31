package io.etclovg.codepilot.data;

/**
 * 灰度对比结果。
 * <p>对应书中 Ch13 §13.3 —— 数据飞轮 Evaluate 阶段的灰度指标对比。
 * <p>由 {@link MetricsEvaluator#scheduleComparison} 在观测窗口结束后回调产出，
 * 用于决策是全量发布（accuracyDelta &gt; 0）还是回滚（edgeDegradation &gt; 0.02）。
 *
 * @param accuracyDelta   准确率增量（新版本 - 旧版本）
 * @param edgeDegradation 边缘案例准确率退化幅度（>0.02 触发人工审核）
 */
public record ComparisonResult(double accuracyDelta, double edgeDegradation) {

    /**
     * 准确率增量访问器。
     *
     * @return 准确率增量
     */
    @Override
    public double accuracyDelta() {
        return accuracyDelta;
    }

    /**
     * 边缘案例退化访问器。
     *
     * @return 边缘案例退化幅度
     */
    @Override
    public double edgeDegradation() {
        return edgeDegradation;
    }
}
