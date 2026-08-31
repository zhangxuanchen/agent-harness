package io.etclovg.codepilot.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可解性评估器。
 * <p>对应书中 Ch09 §9.4 —— 任务可解性评估。
 * <p>基于任务类型和历史成功率，评估新任务的可解性：
 * <ul>
 *   <li><b>任务分类</b>：将任务归类（代码生成、数据分析、文本摘要等）</li>
 *   <li><b>历史查询</b>：查询同类任务的历史成功率</li>
 *   <li><b>相似度匹配</b>：基于关键词/embedding 匹配相似历史任务</li>
 *   <li><b>概率估算</b>：综合多种因素给出可解性概率</li>
 * </ul>
 */
@Component
public class SolvabilityEstimator {

    private static final Logger log = LoggerFactory.getLogger(SolvabilityEstimator.class);

    /**
     * 任务类型枚举。
     */
    public enum TaskType {
        CODE_GENERATION("代码生成", 0.85),
        CODE_DEBUGGING("代码调试", 0.75),
        DATA_ANALYSIS("数据分析", 0.80),
        TEXT_SUMMARIZATION("文本摘要", 0.90),
        TRANSLATION("翻译", 0.92),
        MATH_PROBLEM("数学问题", 0.70),
        CREATIVE_WRITING("创意写作", 0.65),
        REASONING("逻辑推理", 0.72),
        MULTI_STEP_PLANNING("多步规划", 0.55),
        TOOL_USE("工具使用", 0.78),
        UNKNOWN("未知类型", 0.50);

        private final String displayName;
        private final double baseSuccessRate;

        TaskType(String displayName, double baseSuccessRate) {
            this.displayName = displayName;
            this.baseSuccessRate = baseSuccessRate;
        }

        public String getDisplayName() {
            return displayName;
        }

        public double getBaseSuccessRate() {
            return baseSuccessRate;
        }
    }

    /**
     * 可解性评估结果。
     */
    public record SolvabilityResult(
            String taskId,
            String taskDescription,
            TaskType taskType,
            double estimatedSuccessRate,
            double confidence,
            List<SimilarTask> similarTasks,
            List<String> keyFactors,
            Instant evaluatedAt
    ) {
        public boolean isLikelySolvable(double threshold) {
            return estimatedSuccessRate >= threshold;
        }
    }

    /**
     * 历史相似任务。
     */
    public record SimilarTask(
            String taskId,
            String description,
            TaskType taskType,
            double successRate,
            double similarityScore,
            int executionCount
    ) {}

    /**
     * 历史任务记录。
     */
    public record HistoryRecord(
            String taskId,
            TaskType taskType,
            String description,
            boolean success,
            long executionTimeMs,
            List<String> keywords,
            Instant timestamp
    ) {}

    /** 历史任务库 */
    private final List<HistoryRecord> history = Collections.synchronizedList(new ArrayList<>());

    /** 任务类型关键词映射 */
    private final Map<TaskType, List<String>> typeKeywords = new ConcurrentHashMap<>();

    /** 分类阈值 */
    private double defaultConfidenceThreshold = 0.7;

    public SolvabilityEstimator() {
        initializeTypeKeywords();
        log.info("[SolvabilityEstimator] 可解性评估器初始化完成, taskTypes={}", TaskType.values().length);
    }

    /**
     * 评估任务可解性。
     *
     * @param taskDescription 任务描述
     * @return 可解性评估结果
     */
    public SolvabilityResult estimate(String taskDescription) {
        String taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[SolvabilityEstimator] ========== 开始可解性评估 ==========");
        log.info("[SolvabilityEstimator] 评估参数: taskId={}, descriptionLength={}",
                taskId, taskDescription != null ? taskDescription.length() : 0);

        if (taskDescription == null || taskDescription.isBlank()) {
            log.warn("[SolvabilityEstimator] 空任务描述，返回默认评估结果");
            return new SolvabilityResult(
                    taskId, "", TaskType.UNKNOWN, 0.5, 0.0,
                    Collections.emptyList(), List.of("任务描述为空"), Instant.now()
            );
        }

        // 1. 分类任务类型
        TaskType taskType = classifyTask(taskDescription);
        log.info("[SolvabilityEstimator] 任务分类: taskId={}, taskType={}, baseSuccessRate={}",
                taskId, taskType.getDisplayName(), taskType.getBaseSuccessRate());

        // 2. 提取关键词
        List<String> keywords = extractKeywords(taskDescription);
        log.info("[SolvabilityEstimator] 提取关键词: taskId={}, keywords={}", taskId, keywords);

        // 3. 查询相似历史任务
        List<SimilarTask> similarTasks = findSimilarTasks(taskType, keywords);
        log.info("[SolvabilityEstimator] 相似任务匹配: taskId={}, matchCount={}", taskId, similarTasks.size());

        // 4. 计算预估成功率
        double estimatedRate = calculateSuccessRate(taskType, similarTasks);
        double confidence = calculateConfidence(similarTasks);

        // 5. 识别关键因素
        List<String> keyFactors = identifyKeyFactors(taskType, similarTasks, estimatedRate);

        log.info("[SolvabilityEstimator] ========== 可解性评估完成 ==========");
        log.info("[SolvabilityEstimator] 评估结果: taskId={}, estimatedRate={}, confidence={}, isSolvable(0.7)={}",
                taskId, String.format("%.2f", estimatedRate), String.format("%.2f", confidence),
                estimatedRate >= defaultConfidenceThreshold);

        return new SolvabilityResult(
                taskId, taskDescription, taskType, estimatedRate, confidence,
                similarTasks, keyFactors, Instant.now()
        );
    }

    /**
     * 添加历史任务记录。
     */
    public void recordExecution(HistoryRecord record) {
        history.add(record);
        if (history.size() > 10000) {
            history.subList(0, history.size() - 10000).clear();
        }
        log.debug("[SolvabilityEstimator] 记录历史任务: taskId={}, type={}, success={}",
                record.taskId(), record.taskType().getDisplayName(), record.success());
    }

    /**
     * 获取历史统计。
     */
    public Map<TaskType, Map<String, Double>> getStatistics() {
        Map<TaskType, List<HistoryRecord>> byType = new EnumMap<>(TaskType.class);
        for (HistoryRecord record : history) {
            byType.computeIfAbsent(record.taskType(), k -> new ArrayList<>()).add(record);
        }

        Map<TaskType, Map<String, Double>> stats = new EnumMap<>(TaskType.class);
        for (Map.Entry<TaskType, List<HistoryRecord>> entry : byType.entrySet()) {
            List<HistoryRecord> records = entry.getValue();
            long successCount = records.stream().filter(HistoryRecord::success).count();
            double rate = records.isEmpty() ? 0 : (double) successCount / records.size();

            Map<String, Double> typeStats = new LinkedHashMap<>();
            typeStats.put("successRate", rate);
            typeStats.put("totalCount", (double) records.size());
            typeStats.put("successCount", (double) successCount);

            stats.put(entry.getKey(), typeStats);
        }

        return stats;
    }

    // ---- 内部方法 ----

    private TaskType classifyTask(String description) {
        String lowerDesc = description.toLowerCase();

        // 基于关键词匹配任务类型
        for (Map.Entry<TaskType, List<String>> entry : typeKeywords.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (lowerDesc.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }

        // 基于长度和复杂度的启发式判断
        if (description.length() > 500) {
            return TaskType.MULTI_STEP_PLANNING;
        }

        return TaskType.UNKNOWN;
    }

    private List<String> extractKeywords(String description) {
        List<String> keywords = new ArrayList<>();
        String[] words = description.toLowerCase().split("[\\s,，。？！?！.。;；:：]+");

        for (String word : words) {
            if (word.length() >= 2) {
                keywords.add(word);
            }
        }

        // 提取完整的技术术语
        String[] multiWordTerms = description.toLowerCase().split("[\\n\\r]+");
        for (String term : multiWordTerms) {
            if (term.length() >= 3 && term.length() <= 30) {
                keywords.add(term.trim());
            }
        }

        return keywords.stream().distinct().limit(10).toList();
    }

    private List<SimilarTask> findSimilarTasks(TaskType taskType, List<String> keywords) {
        List<SimilarTask> similar = new ArrayList<>();

        // 筛选同类型历史任务
        List<HistoryRecord> sameType = history.stream()
                .filter(r -> r.taskType() == taskType)
                .toList();

        // 计算相似度
        for (HistoryRecord record : sameType) {
            Set<String> recordKeywords = new HashSet<>(record.keywords());
            Set<String> queryKeywords = new HashSet<>(keywords);

            // Jaccard 相似度
            Set<String> intersection = new HashSet<>(queryKeywords);
            intersection.retainAll(recordKeywords);
            Set<String> union = new HashSet<>(queryKeywords);
            union.addAll(recordKeywords);

            double similarity = union.isEmpty() ? 0 : (double) intersection.size() / union.size();

            if (similarity > 0.1) {
                similar.add(new SimilarTask(
                        record.taskId(), record.description(), record.taskType(),
                        record.success() ? 1.0 : 0.0, similarity, 1
                ));
            }
        }

        // 按相似度排序
        similar.sort((a, b) -> Double.compare(b.similarityScore(), a.similarityScore()));

        // 取 Top 5
        return similar.stream().limit(5).toList();
    }

    private double calculateSuccessRate(TaskType taskType, List<SimilarTask> similarTasks) {
        if (similarTasks.isEmpty()) {
            log.debug("[SolvabilityEstimator] 无相似任务，使用基础成功率: type={}, rate={}",
                    taskType.getDisplayName(), taskType.getBaseSuccessRate());
            return taskType.getBaseSuccessRate();
        }

        // 加权平均：相似度越高权重越大
        double totalWeight = 0;
        double weightedSum = 0;

        for (SimilarTask task : similarTasks) {
            double weight = task.similarityScore() * task.executionCount();
            weightedSum += task.successRate() * weight;
            totalWeight += weight;
        }

        double historicalRate = totalWeight > 0 ? weightedSum / totalWeight : 0;

        // 融合基础成功率和历史成功率
        double alpha = 0.3; // 基础成功率权重
        double finalRate = alpha * taskType.getBaseSuccessRate() + (1 - alpha) * historicalRate;

        log.debug("[SolvabilityEstimator] 成功率计算: baseRate={}, historicalRate={}, finalRate={}",
                taskType.getBaseSuccessRate(), historicalRate, finalRate);

        return Math.max(0, Math.min(1, finalRate));
    }

    private double calculateConfidence(List<SimilarTask> similarTasks) {
        if (similarTasks.isEmpty()) {
            return 0.3; // 低置信度
        }

        // 置信度基于：相似任务数量 + 平均相似度
        double avgSimilarity = similarTasks.stream()
                .mapToDouble(SimilarTask::similarityScore)
                .average()
                .orElse(0);

        int count = similarTasks.size();

        // 对数增长：数量越多置信度越高，但递减
        double countFactor = Math.min(1.0, Math.log10(count + 1) / Math.log10(10));
        double similarityFactor = avgSimilarity;

        double confidence = 0.5 * countFactor + 0.5 * similarityFactor;
        return Math.max(0, Math.min(1, confidence));
    }

    private List<String> identifyKeyFactors(TaskType taskType, List<SimilarTask> similarTasks, double estimatedRate) {
        List<String> factors = new ArrayList<>();

        // 基础因素
        factors.add("任务类型: " + taskType.getDisplayName());
        factors.add("基础成功率: " + String.format("%.0f%%", taskType.getBaseSuccessRate() * 100));

        // 历史因素
        if (!similarTasks.isEmpty()) {
            factors.add("历史参照: " + similarTasks.size() + " 个相似任务");
            double avgSimilarity = similarTasks.stream()
                    .mapToDouble(SimilarTask::similarityScore).average().orElse(0);
            factors.add("平均相似度: " + String.format("%.0f%%", avgSimilarity * 100));
        } else {
            factors.add("无历史参照数据");
        }

        // 风险因素
        if (estimatedRate < 0.5) {
            factors.add("⚠️ 预估成功率低于 50%，属于高风险任务");
        } else if (estimatedRate < 0.7) {
            factors.add("⚡ 预估成功率中等，需要额外关注");
        } else {
            factors.add("✅ 预估成功率良好");
        }

        return factors;
    }

    private void initializeTypeKeywords() {
        typeKeywords.put(TaskType.CODE_GENERATION, List.of("代码", "code", "函数", "function", "编程", "实现"));
        typeKeywords.put(TaskType.CODE_DEBUGGING, List.of("调试", "debug", "错误", "error", "bug", "修复", "fix"));
        typeKeywords.put(TaskType.DATA_ANALYSIS, List.of("分析", "analysis", "数据", "data", "统计", "报表"));
        typeKeywords.put(TaskType.TEXT_SUMMARIZATION, List.of("摘要", "summary", "总结", "概括", "提炼"));
        typeKeywords.put(TaskType.TRANSLATION, List.of("翻译", "translate", "转换", "中文", "英文"));
        typeKeywords.put(TaskType.MATH_PROBLEM, List.of("计算", "calculate", "数学", "方程", "公式"));
        typeKeywords.put(TaskType.CREATIVE_WRITING, List.of("写", "write", "创作", "故事", "文章", "文案"));
        typeKeywords.put(TaskType.REASONING, List.of("推理", "reasoning", "分析", "逻辑", "比较"));
        typeKeywords.put(TaskType.MULTI_STEP_PLANNING, List.of("规划", "plan", "步骤", "分步", "流程"));
        typeKeywords.put(TaskType.TOOL_USE, List.of("工具", "tool", "调用", "执行", "command"));
        log.info("[SolvabilityEstimator] 任务类型关键词初始化完成, typeCount={}", typeKeywords.size());
    }

    public void setDefaultConfidenceThreshold(double threshold) {
        this.defaultConfidenceThreshold = threshold;
        log.info("[SolvabilityEstimator] 置信度阈值已更新: old={}, new={}", defaultConfidenceThreshold, threshold);
    }

    public double getDefaultConfidenceThreshold() {
        return defaultConfidenceThreshold;
    }
}
