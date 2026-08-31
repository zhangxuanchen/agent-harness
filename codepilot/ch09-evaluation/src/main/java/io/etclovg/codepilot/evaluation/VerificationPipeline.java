package io.etclovg.codepilot.evaluation;

import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * V 层装配配置。
 *
 * <p>将 V 层的所有 Advisor（均继承 {@link AbstractLayerMiddleware}）装配为有序的中间件链，
 * 串联起"就绪检查 → 嵌入式评判 → 评估引擎 → 回归测试 → LLM 评判"五道门禁。
 *
 * <p>对应书中 Ch9 §9.2 — 四阶段质量控制循环的工程装配。
 *
 * <h3>装配顺序</h3>
 * <ol>
 *   <li>{@link ReadinessCheckAdvisor} — 执行前：三维度评分（清晰度+完整性+可解性），低于 3.0 拦截</li>
 *   <li>{@link EmbeddedValidationAdvisor} — 执行中：三重检查（安全+格式+质量）+ 三级修正</li>
 *   <li>{@link EvaluationAdvisor} — 全流程：五阶段评估引擎（ANCHOR/READY/EXECUTE/JUDGE/REGRESS）</li>
 *   <li>{@link RegressionRunner} — 变更后：回归对比 + Welch's t 检验故障告警</li>
 *   <li>{@link LLMJudgeAdvisor} — 评判：LLM-as-Judge 偏差治理（AB/BA 交换 + 长度截断 + 多次投票）</li>
 * </ol>
 *
 * <p>注：{@link ShadowTrafficRouter} 和 {@link ABTestRunner} 为在线评估组件，
 * 不在中间件链中，通过独立的 Service 装配使用。
 */
@Configuration
public class VerificationPipeline {

    private static final Logger log = LoggerFactory.getLogger(VerificationPipeline.class);

    /**
     * V 层中间件链装配——按执行顺序串联五道门禁。
     *
     * @param readiness  就绪检查 Advisor
     * @param embedded   嵌入式评判 Advisor
     * @param evaluation 评估引擎 Advisor
     * @param regression 回归测试执行器
     * @param judge      LLM-as-Judge 偏差治理
     * @return 有序的 V 层中间件链
     */
    @Bean
    public List<AbstractLayerMiddleware> verificationLayer(
            ReadinessCheckAdvisor readiness,
            EmbeddedValidationAdvisor embedded,
            EvaluationAdvisor evaluation,
            RegressionRunner regression,
            LLMJudgeAdvisor judge) {

        List<AbstractLayerMiddleware> chain = List.of(
                readiness,    // 1. 执行前：就绪检查（三维度评分+门禁）
                embedded,     // 2. 执行中：三重检查+三级修正
                evaluation,   // 3. 全流程：五阶段评估引擎（内部 EXECUTE+JUDGE 子步骤）
                regression,   // 4. 变更后：回归对比+故障告警
                judge         // 5. 评判：LLM-as-Judge 偏差治理
        );

        log.info("[VerificationPipeline] V 层中间件链装配完成: {} 道门禁", chain.size());
        return chain;
    }
}
