package io.etclovg.codepilot.evaluation;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 影子流量路由器。
 *
 * <p>拦截生产请求，按比例（默认 5%）复制到影子通道，
 * 新版本并行处理但不返回结果给用户，只记录对比结果。
 * 累积足够样本后自动运行配对 t 检验，达标后渐进扩流。
 *
 * <p>对应书中 Ch9 §9.5.1 — 影子流量评估的工程实现。
 *
 * <h3>核心逻辑</h3>
 * <ul>
 *   <li>旧 Agent 对 100% 请求返回结果给用户</li>
 *   <li>5% 请求在主线之外额外复制给新 Agent 并行对比</li>
 *   <li>新 Agent 独立执行，结果仅记录不返回</li>
 *   <li>累积 ≥ 100 样本后自动配对 t 检验</li>
 *   <li>p < 0.05 + Cohen's d > 0.2 → 渐进扩流 (5%→20%→50%→100%)</li>
 * </ul>
 */
@Service
public class ShadowTrafficRouter {

    private static final Logger log = LoggerFactory.getLogger(ShadowTrafficRouter.class);

    private static final double INITIAL_SHADOW_RATIO = 0.05;
    private static final int MIN_SAMPLES_FOR_TEST = 100;
    private static final double P_VALUE_THRESHOLD = 0.05;
    private static final double COHENS_D_THRESHOLD = 0.2;

    private final ReActAgent productionAgent;
    private final ReActAgent shadowAgent;
    private final EvaluationAdvisor evaluator;

    private double shadowRatio = INITIAL_SHADOW_RATIO;
    private final List<ComparisonRecord> comparisons = new CopyOnWriteArrayList<>();
    private final Map<String, RuntimeContext> productionContexts = new ConcurrentHashMap<>();
    private final AtomicInteger testRunCount = new AtomicInteger(0);

    public ShadowTrafficRouter(ReActAgent productionAgent,
                               ReActAgent shadowAgent,
                               EvaluationAdvisor evaluator) {
        this.productionAgent = productionAgent;
        this.shadowAgent = shadowAgent;
        this.evaluator = evaluator;
        log.info("[ShadowTrafficRouter] 影子流量路由器初始化完成，初始比例={}%",
                shadowRatio * 100);
    }

    /**
     * 处理生产请求，同时按比例影子复制。
     */
    public ShadowResult route(String userInput, RuntimeContext productionRc) {
        String sessionId = UUID.randomUUID().toString();
        productionRc.put("session.id", sessionId);

        // 主线：生产 Agent 执行
        Msg productionResult = productionAgent.call(userInput, productionRc).block();
        productionContexts.put(sessionId, productionRc);

        // 按比例复制到影子通道
        boolean shadowed = false;
        if (ThreadLocalRandom.current().nextDouble() < shadowRatio) {
            copyToShadow(userInput, sessionId, productionRc, productionResult);
            shadowed = true;
        }

        log.debug("[ShadowTrafficRouter] 会话 {} 完成，影子复制={}", sessionId, shadowed);
        return new ShadowResult(sessionId, productionResult, shadowed);
    }

    /**
     * 异步执行影子流量对比。
     */
    private void copyToShadow(String userInput, String sessionId,
                               RuntimeContext productionRc, Msg productionResult) {
        try {
            // 独立的 RuntimeContext
            RuntimeContext shadowRc = RuntimeContext.builder()
                    .sessionId("shadow-" + sessionId)
                    .build();
            shadowRc.put("shadow.of", sessionId);
            shadowRc.put("shadow.enabled", true);

            // 影子 Agent 执行
            Msg shadowResult = shadowAgent.call(userInput, shadowRc).block();

            // 评估新旧输出质量
            String prodOutput = productionResult != null ? productionResult.getTextContent() : "";
            String shadowOutput = shadowResult != null ? shadowResult.getTextContent() : "";

            double prodScore = scoreOutput(prodOutput);
            double shadowScore = scoreOutput(shadowOutput);

            // 记录对比结果
            ComparisonRecord record = new ComparisonRecord(
                    sessionId, prodScore, shadowScore,
                    prodOutput.length(), shadowOutput.length(),
                    System.currentTimeMillis()
            );
            comparisons.add(record);

            int totalComparisons = comparisons.size();
            log.info("[ShadowTrafficRouter] 影子对比 #{}: prod={:.2f} shadow={:.2f} Δ={:.2f}",
                    totalComparisons, prodScore, shadowScore,
                    shadowScore - prodScore);

            // 累积足够样本时自动运行统计检验
            if (totalComparisons >= MIN_SAMPLES_FOR_TEST
                    && testRunCount.incrementAndGet() <= totalComparisons / MIN_SAMPLES_FOR_TEST) {
                runPairedTTestAndDecide();
            }
        } catch (Exception e) {
            log.error("[ShadowTrafficRouter] 影子流量执行异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 使用简单启发式评分（实际应接入 EvaluationAdvisor）。
     */
    private double scoreOutput(String output) {
        if (output == null || output.isBlank()) return 0.0;
        double score = 0.5;
        if (output.length() > 50) score += 0.1;
        if (output.length() > 200) score += 0.1;
        if (output.contains("```")) score += 0.1;
        if (output.split("\n").length > 3) score += 0.1;
        return Math.min(1.0, score);
    }

    /**
     * 配对 t 检验 + 扩流决策。
     */
    private void runPairedTTestAndDecide() {
        if (comparisons.size() < MIN_SAMPLES_FOR_TEST) return;

        List<Double> diffs = new ArrayList<>();
        for (ComparisonRecord r : comparisons) {
            diffs.add(r.shadowScore() - r.prodScore());
        }

        double meanDiff = calculateMean(diffs);
        double stdDiff = calculateStdDev(diffs);

        log.info("[ShadowTrafficRouter] 配对 t 检验: n={} meanDiff={:.4f} stdDiff={:.4f}",
                diffs.size(), meanDiff, stdDiff);

        if (stdDiff == 0) {
            log.info("[ShadowTrafficRouter] 标准差为 0，跳过统计检验");
            return;
        }

        double tStatistic = meanDiff / (stdDiff / Math.sqrt(diffs.size()));
        double cohensD = meanDiff / stdDiff;

        // 使用 Apache Commons Math 计算 p 值
        try {
            org.apache.commons.math3.distribution.TDistribution tDist =
                    new org.apache.commons.math3.distribution.TDistribution(diffs.size() - 1);
            double pValue = 1.0 - tDist.cumulativeProbability(Math.abs(tStatistic));

            log.info("[ShadowTrafficRouter] 统计结果: t={:.4f} p={:.4f} d={:.4f}",
                    tStatistic, pValue, cohensD);

            if (pValue < P_VALUE_THRESHOLD && cohensD > COHENS_D_THRESHOLD) {
                // 新版本显著优于旧版本 → 扩流
                double newRatio = Math.min(shadowRatio * 4, 1.0);
                log.info("[ShadowTrafficRouter] ✅ 新版本显著优于旧版本 → 扩流: {}% → {}%",
                        shadowRatio * 100, newRatio * 100);
                shadowRatio = newRatio;
            } else if (pValue < P_VALUE_THRESHOLD && cohensD < -COHENS_D_THRESHOLD) {
                // 新版本显著差于旧版本 → 回退
                log.warn("[ShadowTrafficRouter] ❌ 新版本显著差于旧版本 → 保持 {}% 或回退",
                        shadowRatio * 100);
                shadowRatio = Math.max(shadowRatio / 4, 0.01);
            } else {
                log.info("[ShadowTrafficRouter] ➖ 差异不显著 (p={:.4f}, d={:.4f})，保持 {}%",
                        pValue, cohensD, shadowRatio * 100);
            }
        } catch (Exception e) {
            log.error("[ShadowTrafficRouter] t 检验计算失败: {}", e.getMessage());
        }
    }

    private double calculateMean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double calculateStdDev(List<Double> values) {
        double mean = calculateMean(values);
        double sumSq = 0;
        for (double v : values) {
            sumSq += (v - mean) * (v - mean);
        }
        return values.size() > 1 ? Math.sqrt(sumSq / (values.size() - 1)) : 0;
    }

    // ========== 查询接口 ==========

    public double getShadowRatio() { return shadowRatio; }

    public int getComparisonCount() { return comparisons.size(); }

    public double getLatestProdScore() {
        return comparisons.isEmpty() ? 0 : comparisons.get(comparisons.size() - 1).prodScore();
    }

    public double getLatestShadowScore() {
        return comparisons.isEmpty() ? 0 : comparisons.get(comparisons.size() - 1).shadowScore();
    }

    /**
     * 影子流量路由结果。
     */
    public record ShadowResult(String sessionId, Msg productionResult, boolean shadowed) {}

    /**
     * 新旧版本对比记录。
     */
    public record ComparisonRecord(
            String sessionId, double prodScore, double shadowScore,
            int prodLength, int shadowLength, long timestamp
    ) {}
}
