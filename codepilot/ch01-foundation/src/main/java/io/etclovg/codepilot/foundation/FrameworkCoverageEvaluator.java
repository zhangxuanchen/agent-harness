package io.etclovg.codepilot.foundation;

import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 框架覆盖率评估器。
 * <p>对应书中 Ch01 §1.4 —— 评估 Harness 在 ETCLOVG 七层上的覆盖程度。
 */
@Component
public class FrameworkCoverageEvaluator {

    private final Map<Layer, FrameworkCoverageRecord> coverageRecords = new EnumMap<>(Layer.class);

    /**
     * 评估框架在各层的覆盖率。
     *
     * @param presentLayers 已实现的层集合
     * @return 各层覆盖率记录列表
     */
    public List<FrameworkCoverageRecord> evaluate(Set<Layer> presentLayers) {
        List<FrameworkCoverageRecord> results = new ArrayList<>();
        for (Layer layer : Layer.values()) {
            int implemented = presentLayers.contains(layer) ? 1 : 0;
            FrameworkCoverageRecord record = new FrameworkCoverageRecord(
                    layer, implemented, implemented == 1 ? 1.0 : 0.0,
                    List.of(), "OK"
            );
            coverageRecords.put(layer, record);
            results.add(record);
        }
        return results;
    }

    /**
     * 获取总体覆盖率百分比。
     */
    public double getOverallCoverage() {
        long implemented = coverageRecords.values().stream()
                .filter(r -> r.coverageScore() > 0)
                .count();
        return (double) implemented / Layer.values().length * 100;
    }
}