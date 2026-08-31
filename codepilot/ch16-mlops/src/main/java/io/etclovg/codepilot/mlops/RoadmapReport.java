package io.etclovg.codepilot.mlops;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 路线图评估报告。
 * <p>对应书中 Ch16 §16.6 —— 四阶段成熟度路线图评估的输出报告。
 *
 * <p>汇总当前所处阶段、目标阶段、各 Harness 层成熟度评分、未满足的就绪条件、
 * 推荐行动项与距下一阶段的估算周数，由 {@link RoadmapAssessor#assess} 通过
 * {@link #builder()} 构建后返回。
 *
 * <p>采用可变构建器模式（书中 §16.6 {@code RoadmapReport.builder()...build()}），
 * 字段逐一链式设置后由 {@link Builder#build()} 冻结为只读实例。
 */
public final class RoadmapReport {

    private final Stage currentStage;
    private final Stage targetStage;
    private final Map<HarnessLayer, Integer> maturityScores;
    private final List<ReadinessItem> unmetConditions;
    private final List<ActionItem> recommendedActions;
    private final int estimatedTimeToNextStage;
    private final Instant generatedAt;

    private RoadmapReport(Builder b) {
        this.currentStage = b.currentStage;
        this.targetStage = b.targetStage;
        this.maturityScores = b.maturityScores == null ? Map.of() : Map.copyOf(b.maturityScores);
        this.unmetConditions = b.unmetConditions == null ? List.of() : List.copyOf(b.unmetConditions);
        this.recommendedActions = b.recommendedActions == null ? List.of() : List.copyOf(b.recommendedActions);
        this.estimatedTimeToNextStage = b.estimatedTimeToNextStage;
        this.generatedAt = b.generatedAt == null ? Instant.now() : b.generatedAt;
    }

    public Stage currentStage() { return currentStage; }
    public Stage targetStage() { return targetStage; }
    public Map<HarnessLayer, Integer> maturityScores() { return maturityScores; }
    public List<ReadinessItem> unmetConditions() { return unmetConditions; }
    public List<ActionItem> recommendedActions() { return recommendedActions; }
    public int estimatedTimeToNextStage() { return estimatedTimeToNextStage; }
    public Instant generatedAt() { return generatedAt; }

    /** 是否已处于演进末阶（无下一阶段目标）。 */
    public boolean isAtFinalStage() {
        return currentStage == Stage.EVOLVE;
    }

    /** 路线图报告构建器。对应书中 §16.6 {@code RoadmapReport.builder()}。 */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Stage currentStage;
        private Stage targetStage;
        private Map<HarnessLayer, Integer> maturityScores;
        private List<ReadinessItem> unmetConditions;
        private List<ActionItem> recommendedActions;
        private int estimatedTimeToNextStage;
        private Instant generatedAt;

        public Builder currentStage(Stage s) { this.currentStage = s; return this; }
        public Builder targetStage(Stage s) { this.targetStage = s; return this; }
        public Builder maturityScores(Map<HarnessLayer, Integer> m) { this.maturityScores = m; return this; }
        public Builder unmetConditions(List<ReadinessItem> u) { this.unmetConditions = u; return this; }
        public Builder recommendedActions(List<ActionItem> a) { this.recommendedActions = a; return this; }
        public Builder estimatedTimeToNextStage(int weeks) { this.estimatedTimeToNextStage = weeks; return this; }
        public Builder generatedAt(Instant at) { this.generatedAt = at; return this; }

        public RoadmapReport build() {
            return new RoadmapReport(this);
        }
    }
}
