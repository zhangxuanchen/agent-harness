package io.etclovg.codepilot.future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 上下文质量评估器。
 * <p>对应书中 Ch19 §19.2 —— 上下文质量评估。
 * <p>从相关性、完整性、冗余度等维度评估注入上下文的质量，
 * 指导上下文管理中间件的裁剪与增强策略。
 */
@Component
public class ContextQualityEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ContextQualityEvaluator.class);

    /**
     * 评估上下文质量得分。
     *
     * @param context 上下文文本
     * @return 质量得分（0-1）
     */
    public double evaluate(String context) {
        if (context == null || context.isBlank()) {
            return 0.0;
        }
        double score = Math.min(1.0, context.length() / 2000.0);
        log.debug("上下文质量评估: length={}, score={}", context.length(), score);
        return score;
    }
}
