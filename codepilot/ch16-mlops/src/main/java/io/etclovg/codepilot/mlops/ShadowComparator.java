package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 影子流量比较器。
 * <p>对应书中 Ch16 §16.5 —— 影子流量结果的对比分析。
 * <p>对比线上版本与候选版本在相同请求上的输出，统计一致率与差异分布，
 * 供发布决策参考。
 */
@Component
public class ShadowComparator {

    private static final Logger log = LoggerFactory.getLogger(ShadowComparator.class);

    /**
     * 比较影子流量结果。
     *
     * @param baselineOutputs 基线输出列表
     * @param candidateOutputs 候选输出列表
     * @return 比较结果
     */
    public ShadowComparisonResult compare(List<String> baselineOutputs, List<String> candidateOutputs) {
        if (baselineOutputs == null || candidateOutputs == null
                || baselineOutputs.size() != candidateOutputs.size()
                || baselineOutputs.isEmpty()) {
            return ShadowComparisonResult.incomparable();
        }
        int match = 0;
        for (int i = 0; i < baselineOutputs.size(); i++) {
            if (baselineOutputs.get(i).equals(candidateOutputs.get(i))) {
                match++;
            }
        }
        double consistency = (double) match / baselineOutputs.size();
        int diff = baselineOutputs.size() - match;
        log.info("影子流量对比: total={}, match={}, consistency={}", baselineOutputs.size(), match, consistency);
        // 仅做字符串级一致率统计；幻觉/质量分由 ShadowComparator 下游（llm-as-judge）模块填充
        return new ShadowComparisonResult(true, consistency, 0.0, 0.0, 0.0, diff,
                "对比完成: 一致率=" + String.format("%.2f", consistency * 100) + "%");
    }

    /**
     * 按版本 ID 与观察窗口比较影子流量结果。
     * <p>对应书中 §16.5 {@code comparator.compare(currentVersion.getId(), newVersion.getId(), shadowRouter.getShadowDuration())}——
     * 从影子流量存储中按窗口拉取双版本输出做 A/B 对比，检测成功率、幻觉率、响应质量三维度
     * 的统计显著差异。教学桩返回健康结果（无显著退化），生产实现对接 llm-as-judge 与统计检验。
     *
     * @param currentVersionId 线上稳定版本 ID
     * @param newVersionId     候选版本 ID
     * @param window           观察窗口时长
     * @return 对比结果
     */
    public ShadowComparisonResult compare(String currentVersionId, String newVersionId, Duration window) {
        log.info("影子流量 A/B 对比: stable={}, candidate={}, window={}h",
                currentVersionId, newVersionId, window == null ? 0 : window.toHours());
        // 教学桩：生产实现应从影子流量存储拉取该窗口内双版本输出，做一致率 + 显著性检验
        return ShadowComparisonResult.healthy();
    }
}
