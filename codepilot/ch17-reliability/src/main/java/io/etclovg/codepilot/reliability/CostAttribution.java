package io.etclovg.codepilot.reliability;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 成本归因：按任务/用户/模型维度追踪 Agent 成本
 * 对应书中 Ch17 — 生产监控可靠性与成本
 *
 * <p>成本归因的核心问题："谁烧了钱"
 * 解决方案：按任务、用户、模型、时间维度建立多级成本追踪。
 */
@Component
public class CostAttribution {

    public record CostEntry(String taskId, String userId, String model, long inputTokens, long outputTokens, double timestamp) {}

    private final Map<String, List<CostEntry>> taskCosts = new LinkedHashMap<>();
    private final Map<String, List<CostEntry>> userCosts = new LinkedHashMap<>();
    private final Map<String, List<CostEntry>> modelCosts = new LinkedHashMap<>();

    public void recordCost(CostEntry entry) {
        taskCosts.computeIfAbsent(entry.taskId(), k -> new ArrayList<>()).add(entry);
        userCosts.computeIfAbsent(entry.userId(), k -> new ArrayList<>()).add(entry);
        modelCosts.computeIfAbsent(entry.model(), k -> new ArrayList<>()).add(entry);
    }

    /**
     * 按任务归因：识别成本最高的任务
     */
    public List<Map.Entry<String, Double>> topTasksByCost(int topN) {
        Map<String, Double> taskTotals = new LinkedHashMap<>();
        for (Map.Entry<String, List<CostEntry>> entry : taskCosts.entrySet()) {
            double total = entry.getValue().stream()
                .mapToDouble(e -> (e.inputTokens() + e.outputTokens()) * 0.00001)
                .sum();
            taskTotals.put(entry.getKey(), total);
        }

        return taskTotals.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topN)
            .toList();
    }

    /**
     * 成本异常检测：标记超出预期范围的成本
     */
    public List<Map<String, Object>> detectAnomalies(double normalCostThreshold, int minSampleSize) {
        List<Map<String, Object>> anomalies = new ArrayList<>();

        for (Map.Entry<String, List<CostEntry>> entry : taskCosts.entrySet()) {
            List<CostEntry> entries = entry.getValue();
            if (entries.size() < minSampleSize) continue;

            double avgCost = entries.stream()
                .mapToDouble(e -> (e.inputTokens() + e.outputTokens()) * 0.00001)
                .average()
                .orElse(0);

            if (avgCost > normalCostThreshold) {
                Map<String, Object> anomaly = new LinkedHashMap<>();
                anomaly.put("task_id", entry.getKey());
                anomaly.put("avg_cost", avgCost);
                anomaly.put("threshold", normalCostThreshold);
                anomaly.put("severity", avgCost > normalCostThreshold * 2 ? "HIGH" : "MEDIUM");
                anomalies.add(anomaly);
            }
        }

        return anomalies;
    }

    /**
     * 成本优化建议生成
     */
    public List<String> generateOptimizationSuggestions() {
        List<String> suggestions = new ArrayList<>();

        List<Map.Entry<String, Double>> topTasks = topTasksByCost(3);
        if (!topTasks.isEmpty()) {
            suggestions.add("高成本任务Top3: " + topTasks.stream()
                .map(e -> e.getKey() + "(" + String.format("%.2f", e.getValue()) + "元)")
                .toList());
            suggestions.add("建议：对高成本任务引入缓存或模型降级策略");
        }

        Map<String, Long> modelUsage = new LinkedHashMap<>();
        for (Map.Entry<String, List<CostEntry>> entry : modelCosts.entrySet()) {
            long totalTokens = entry.getValue().stream()
                .mapToLong(e -> e.inputTokens() + e.outputTokens())
                .sum();
            modelUsage.put(entry.getKey(), totalTokens);
        }

        modelUsage.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(1)
            .findFirst()
            .ifPresent(e -> suggestions.add("最高消耗模型: " + e.getKey() + " (" + e.getValue() + " tokens)"));

        return suggestions;
    }

    /**
     * 成本预测：基于历史趋势预测未来成本
     */
    public double forecastMonthlyCost(double dailyAverage, int daysPerMonth, double growthRate) {
        return dailyAverage * daysPerMonth * (1 + growthRate);
    }
}
