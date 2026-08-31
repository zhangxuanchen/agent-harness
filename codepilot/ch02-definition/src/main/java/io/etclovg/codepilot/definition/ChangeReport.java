package io.etclovg.codepilot.definition;

import java.util.List;

/**
 * 变更报告。
 * <p>对应书中 Ch02 §2.1 —— 记录评估过程中的变更内容。
 *
 * @param reportId    报告 ID
 * @param changeType  变更类型
 * @param description 变更描述
 * @param filesChanged 变更文件列表
 * @param riskLevel   风险等级
 * @param timestamp   时间戳
 */
public record ChangeReport(
        String reportId,
        String changeType,
        String description,
        List<String> filesChanged,
        String riskLevel,
        long timestamp
) {
    public boolean isHighRisk() {
        return "HIGH".equalsIgnoreCase(riskLevel);
    }
}