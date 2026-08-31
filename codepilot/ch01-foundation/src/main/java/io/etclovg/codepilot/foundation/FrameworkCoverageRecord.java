package io.etclovg.codepilot.foundation;

import io.etclovg.codepilot.core.Layer;

import java.util.List;

/**
 * 框架覆盖率记录。
 * <p>记录单个 ETCLOVG 层的覆盖情况。
 *
 * @param layer         对应的层
 * @param implemented   已实现的组件数
 * @param coverageScore 覆盖得分 (0-1)
 * @param missingItems  缺失的组件列表
 * @param status        状态描述
 */
public record FrameworkCoverageRecord(
        Layer layer,
        int implemented,
        double coverageScore,
        List<String> missingItems,
        String status
) {
}