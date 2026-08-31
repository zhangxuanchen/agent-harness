package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 上下文漂移检测器。
 * 配套仓库教学实现，非 AgentScope 内置。
 *
 * 对应书中 KP 6.4.2 "上下文漂移：目标悄悄被替换"。
 * 漂移和腐烂不同：腐烂是信息被稀释但目标没变，漂移是目标本身偏离了原始方向。
 * 检测方法是比较"原始目标"和"当前行为"在语义空间中的相似度，
 * cosine similarity 低于阈值就告警。
 */
@Component
public class DriftDetector {

    private static final Logger log = LoggerFactory.getLogger(DriftDetector.class);

    /**
     * 不同任务类型的漂移检测阈值。
     * 阈值基于编码 Agent 场景（约 200 个任务样本）的经验值，
     * 读者应在自身场景上用 grid search 验证。
     *
     * - 单一目标（修 bug / 改函数）：目标描述短，稍微偏离就掉得快 → 阈值 0.7
     * - 复合目标（实现完整功能）：子目标多，正常切换会让 cosine 波动 → 阈值 0.5
     * - 探索性任务（调研 / 头脑风暴）：本来就允许大范围探索 → 阈值 0.4
     */
    public enum TaskType {
        SINGLE_GOAL(0.7),
        COMPOUND_GOAL(0.5),
        EXPLORATORY(0.4);

        private final double threshold;

        TaskType(double threshold) {
            this.threshold = threshold;
        }

        public double threshold() {
            return threshold;
        }
    }

    /**
     * 检测当前行为是否偏离了原始目标。
     *
     * @param originalGoal    任务开始时用户给的原始目标（不变）
     * @param currentBehavior 当前正在执行的动作描述（每步更新）
     * @param taskType        任务类型（决定用哪个阈值）
     * @return 检测结果：相似度、是否漂移、漂移等级、修复建议
     */
    public DriftResult detect(String originalGoal,
                              String currentBehavior,
                              TaskType taskType) {
        if (originalGoal == null || currentBehavior == null
                || originalGoal.isBlank() || currentBehavior.isBlank()) {
            return new DriftResult(0.0, false, DriftLevel.NONE,
                    "输入为空，跳过漂移检测");
        }

        // 优先用 embedding 做 cosine similarity；没有 embedding 模型时降级为关键词重叠率
        double similarity = calculateSimilarity(originalGoal, currentBehavior);
        double threshold = taskType.threshold();
        boolean isDrift = similarity < threshold;

        // 漂移等级划分
        DriftLevel level;
        if (!isDrift) {
            level = DriftLevel.NONE;
        } else if (similarity >= threshold - 0.1) {
            level = DriftLevel.LIGHT;
        } else if (similarity >= threshold - 0.25) {
            level = DriftLevel.MEDIUM;
        } else {
            level = DriftLevel.SEVERE;
        }

        String suggestion = switch (level) {
            case NONE -> "目标一致，继续执行";
            case LIGHT -> "轻微漂移，建议在 user message 末尾重述原始目标";
            case MEDIUM -> "中度漂移，暂停执行，用 SESSION INTENT 段强制回归原始目标";
            case SEVERE -> "严重漂移，立即终止，人工确认目标是否变化后再继续";
        };

        log.info("[DriftDetect] 相似度 {:.2f}，阈值 {}（{}），等级 {}",
                similarity, threshold, taskType, level);

        return new DriftResult(similarity, isDrift, level, suggestion);
    }

    /**
     * 计算两段文本的语义相似度。
     * 演示实现：用 Jaccard 关键词重叠率（无需 embedding 模型）。
     * 生产环境应替换为 embedding model 的 cosine similarity。
     */
    private double calculateSimilarity(String a, String b) {
        // 分词：中文取字符二元组，英文取单词
        Set<String> tokensA = tokenize(a);
        Set<String> tokensB = tokenize(b);
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0;

        int intersection = 0;
        for (String t : tokensA) {
            if (tokensB.contains(t)) intersection++;
        }
        int union = tokensA.size() + tokensB.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    /**
     * 字符二元组分词（中英文混合文本的简易分词方案）。
     */
    private Set<String> tokenize(String text) {
        Set<String> result = new HashSet<>();
        // 英文单词
        for (String word : text.toLowerCase().split("[^a-zA-Z0-9]+")) {
            if (word.length() >= 2) result.add(word);
        }
        // 中文字符二元组
        String cn = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < cn.length() - 1; i++) {
            result.add(cn.substring(i, i + 2));
        }
        return result;
    }

    /**
     * 网格搜索（Grid Search）：从标注数据中找出最优阈值。
     *
     * @param labeledData 标注好的数据（每条含相似度分数 + 是否真实漂移）
     * @return 最优阈值（0.3~0.8 之间）+ 对应 F1 分数
     */
    public GridSearchResult gridSearch(List<LabeledDriftCase> labeledData) {
        if (labeledData == null || labeledData.isEmpty()) {
            return new GridSearchResult(0.6, 0.0, 0);
        }
        int total = labeledData.size();
        int trueDriftCount = (int) labeledData.stream().filter(LabeledDriftCase::isTrueDrift).count();

        double bestF1 = -1;
        double bestThreshold = 0.6;

        // 从 0.3 到 0.8，步长 0.1，共 6 个候选
        for (double t = 0.3; t <= 0.81; t += 0.1) {
            double threshold = Math.round(t * 100.0) / 100.0;
            int tp = 0, fp = 0, fn = 0;
            for (LabeledDriftCase c : labeledData) {
                boolean predicted = c.similarity() < threshold;
                if (predicted && c.isTrueDrift()) tp++;
                else if (predicted) fp++;
                else if (c.isTrueDrift()) fn++;
            }
            double precision = (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp);
            double recall = (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn);
            double f1 = (precision + recall) == 0 ? 0.0 : 2 * precision * recall / (precision + recall);

            log.info("[GridSearch] 阈值 {}: P={:.2f} R={:.2f} F1={:.2f}",
                    threshold, precision, recall, f1);

            if (f1 > bestF1) {
                bestF1 = f1;
                bestThreshold = threshold;
            }
        }

        log.info("[GridSearch] 最优阈值 {}，F1={:.2f}，样本量 {}",
                bestThreshold, bestF1, total);
        return new GridSearchResult(bestThreshold, bestF1, total);
    }

    /** 漂移严重等级 */
    public enum DriftLevel {
        NONE,     // 没有漂移
        LIGHT,    // 轻微——提醒即可
        MEDIUM,   // 中度——需要强制回归
        SEVERE    // 严重——立即终止
    }

    /**
     * 漂移检测结果。
     * @param similarity  原始目标与当前行为的语义相似度（0~1）
     * @param isDrift     是否判定为漂移（similarity < 阈值）
     * @param driftLevel  漂移严重等级
     * @param suggestion  修复建议
     */
    public record DriftResult(
            double similarity,
            boolean isDrift,
            DriftLevel driftLevel,
            String suggestion
    ) {}

    /**
     * 标注好的漂移案例（用于 grid search）。
     * @param similarity   预计算的相似度分数
     * @param isTrueDrift  人工标注：这个案例是否真的发生了漂移
     */
    public record LabeledDriftCase(
            double similarity,
            boolean isTrueDrift
    ) {}

    /**
     * Grid search 结果。
     * @param bestThreshold  最优阈值
     * @param bestF1         该阈值下的 F1 分数
     * @param sampleSize     参与训练的样本量
     */
    public record GridSearchResult(
            double bestThreshold,
            double bestF1,
            int sampleSize
    ) {}
}
