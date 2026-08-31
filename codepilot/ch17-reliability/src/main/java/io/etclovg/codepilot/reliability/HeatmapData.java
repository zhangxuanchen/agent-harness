package io.etclovg.codepilot.reliability;

import java.util.List;
import java.util.Map;

/**
 * 热力图数据记录：用于可视化健康度热力图
 * 对应书中 Ch17 §17.5 —— 健康度可视化
 */
public record HeatmapData(
        String title,
        List<String> rowHeaders,
        List<String> colHeaders,
        double[][] values,
        Map<String, String> metadata
) {
    public int rowCount() {
        return rowHeaders.size();
    }

    public int colCount() {
        return colHeaders.size();
    }

    public double getValue(int row, int col) {
        if (values == null || row >= values.length || col >= values[row].length) {
            return 0.0;
        }
        return values[row][col];
    }

    public double maxValue() {
        double max = 0.0;
        if (values != null) {
            for (double[] row : values) {
                for (double val : row) {
                    max = Math.max(max, val);
                }
            }
        }
        return max;
    }

    public double minValue() {
        double min = Double.MAX_VALUE;
        if (values != null) {
            for (double[] row : values) {
                for (double val : row) {
                    min = Math.min(min, val);
                }
            }
        }
        return min == Double.MAX_VALUE ? 0.0 : min;
    }

    public static HeatmapData ofAgentHealth(List<String> agentIds, List<String> metrics) {
        double[][] data = new double[agentIds.size()][metrics.size()];
        for (int i = 0; i < agentIds.size(); i++) {
            for (int j = 0; j < metrics.size(); j++) {
                data[i][j] = 0.5 + Math.random() * 0.5;
            }
        }
        return new HeatmapData(
                "Agent 健康度热力图",
                agentIds, metrics, data,
                Map.of("type", "agent-health", "generated", "auto")
        );
    }
}