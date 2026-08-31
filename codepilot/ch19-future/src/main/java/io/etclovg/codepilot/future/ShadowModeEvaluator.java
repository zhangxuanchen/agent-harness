package io.etclovg.codepilot.future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 影子模式评估器。
 * <p>对应书中 Ch19 §19.5 —— 自我改进的影子模式评估。
 * <p>在影子模式中运行改进后的 Agent，与线上版本并行处理相同输入，
 * 通过 {@link ShadowModeResult} 评估改进是否安全可上线。
 */
@Component
public class ShadowModeEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ShadowModeEvaluator.class);

    /**
     * 评估影子模式结果。
     *
     * @param baselineOutput 基线输出
     * @param shadowOutput   影子输出
     * @return 评估结果
     */
    public ShadowModeResult evaluate(String baselineOutput, String shadowOutput) {
        if (baselineOutput == null || shadowOutput == null) {
            return ShadowModeResult.incomparable();
        }
        boolean consistent = baselineOutput.equals(shadowOutput);
        double score = consistent ? 1.0 : 0.0;
        log.info("影子模式评估: consistent={}, score={}", consistent, score);
        return new ShadowModeResult(true, score, consistent ? "输出一致" : "输出存在差异");
    }
}
