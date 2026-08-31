package io.etclovg.codepilot.reliability;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 成本热力图生成器。
 * <p>对应书中 Ch17 §17.4 —— 生成成本分布的可视化数据。
 */
@Component
public class CostHeatmapGenerator {

    /**
     * 热力图数据。
     */
    public record HeatmapData(
            Map<String, double[]> nodeCosts,
            double maxCost,
            double minCost,
            String timeRange
    ) {}

    /**
     * 生成成本热力图。
     */
    public HeatmapData generate(Map<String, double[]> nodeCosts, String timeRange) {
        double max = nodeCosts.values().stream()
                .mapToDouble(arr -> Arrays.stream(arr).max().orElse(0))
                .max().orElse(0);
        double min = nodeCosts.values().stream()
                .mapToDouble(arr -> Arrays.stream(arr).min().orElse(0))
                .min().orElse(0);
        return new HeatmapData(nodeCosts, max, min, timeRange);
    }
}