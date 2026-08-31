package io.etclovg.codepilot.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 生产监控层 · 管理者四象限计算器。
 *
 * <p>ManagerQuadrantCalculator 从管理者视角评估 Agent 系统的整体健康状况，
 * 通过四个核心维度（成本/价值/质量/风险）形成四象限矩阵，
 * 帮助管理者快速判断系统状态并做出决策。
 *
 * <h3>四象限定义</h3>
 * <ul>
 *   <li><b>Q1（高价值 + 低成本）</b> — 理想状态，应继续保持</li>
 *   <li><b>Q2（高价值 + 高成本）</b> — 需要优化成本（如模型路由、缓存策略）</li>
 *   <li><b>Q3（低价值 + 低成本）</b> — 需要提升价值（如增加功能、改进质量）</li>
 *   <li><b>Q4（低价值 + 高成本）</b> — 需要立即改进（可能需要重构或下线）</li>
 * </ul>
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>按成本/价值/质量/风险四个维度评估 Agent 运行状态</li>
 *   <li>生成四象限矩阵，可视化展示各 Agent 位置</li>
 *   <li>提供改进建议（如"成本过高，建议优化模型路由"）</li>
 *   <li>支持自定义权重和阈值</li>
 *   <li>输出可视化数据（用于 Dashboard 展示）</li>
 * </ul>
 *
 * <p>对应书中 Ch17 §17.4 管理者视角的决策框架。
 */
@Component
public class ManagerQuadrantCalculator {

    private static final Logger log = LoggerFactory.getLogger(ManagerQuadrantCalculator.class);

    /** 指标数据存储：agentId → 指标列表 */
    private final Map<String, List<AgentMetrics>> agentMetricsHistory = new ConcurrentHashMap<>();

    /** 权重配置 */
    private volatile WeightConfig weights = new WeightConfig(0.3, 0.35, 0.2, 0.15);

    /** 阈值配置 */
    private volatile ThresholdConfig thresholds = new ThresholdConfig(
            0.5,    // 价值阈值（高于此为高价值）
            0.5,    // 成本阈值（低于此为低成本）
            0.7,    // 质量阈值
            0.3     // 风险阈值
    );

    /** 自定义评估规则 */
    private final List<EvaluationRule> customRules = new CopyOnWriteArrayList<>();

    public ManagerQuadrantCalculator() {
        initializeDefaultRules();
        log.info("[管理者象限] ManagerQuadrantCalculator 初始化完成");
    }

    // ========== 指标录入 ==========

    /**
     * 录入 Agent 运行指标
     */
    public void recordAgentMetrics(String agentId, AgentMetrics metrics) {
        List<AgentMetrics> history = agentMetricsHistory.computeIfAbsent(
                agentId, k -> Collections.synchronizedList(new ArrayList<>()));
        history.add(metrics);

        // 保留最近 100 条记录
        if (history.size() > 100) {
            history.subList(0, history.size() - 100).clear();
        }

        log.debug("[管理者象限] 录入 Agent {} 指标: successRate={}, avgCost={}, quality={}, secIncident={}",
                agentId,
                String.format("%.2f", metrics.successRate()),
                String.format("%.2f", metrics.avgCostPerTask()),
                String.format("%.2f", metrics.qualityScore()),
                String.format("%.2f", metrics.securityIncidentRate()));
    }

    /**
     * 批量录入 Agent 指标
     */
    public void recordAgentMetricsBatch(String agentId, List<AgentMetrics> metricsList) {
        for (AgentMetrics metrics : metricsList) {
            recordAgentMetrics(agentId, metrics);
        }
    }

    // ========== 四象限计算 ==========

    /**
     * 计算单个 Agent 的象限位置
     */
    public QuadrantResult calculateQuadrant(String agentId) {
        List<AgentMetrics> history = agentMetricsHistory.get(agentId);
        if (history == null || history.isEmpty()) {
            log.warn("[管理者象限] Agent {} 无历史指标数据", agentId);
            return new QuadrantResult(agentId, Quadrant.EMPTY, 0.0, 0.0, 0.0, 0.0,
                    List.of("无数据"), Instant.now());
        }

        // 计算各维度分数
        double valueScore = calculateValueScore(history);
        double costScore = calculateCostScore(history);
        double qualityScore = calculateQualityScore(history);
        double riskScore = calculateRiskScore(history);

        // 确定象限
        Quadrant quadrant = determineQuadrant(valueScore, costScore);

        // 生成改进建议
        List<String> suggestions = generateSuggestions(
                agentId, quadrant, valueScore, costScore, qualityScore, riskScore);

        log.info("[管理者象限] Agent {} 象限: {}, value={}, cost={}, quality={}, risk={}",
                agentId, quadrant,
                String.format("%.2f", valueScore),
                String.format("%.2f", costScore),
                String.format("%.2f", qualityScore),
                String.format("%.2f", riskScore));

        return new QuadrantResult(
                agentId, quadrant,
                valueScore, costScore,
                qualityScore, riskScore,
                suggestions,
                Instant.now()
        );
    }

    /**
     * 计算所有 Agent 的象限矩阵
     */
    public QuadrantMatrix calculateFullMatrix() {
        Map<String, QuadrantResult> results = new LinkedHashMap<>();

        // 聚合各象限的 Agent
        Map<Quadrant, List<String>> quadrantGroups = new EnumMap<>(Quadrant.class);
        for (Quadrant q : Quadrant.values()) {
            quadrantGroups.put(q, new ArrayList<>());
        }

        for (String agentId : agentMetricsHistory.keySet()) {
            QuadrantResult result = calculateQuadrant(agentId);
            results.put(agentId, result);
            quadrantGroups.get(result.quadrant()).add(agentId);
        }

        // 计算统计
        int totalAgents = results.size();
        Map<Quadrant, Double> distribution = new EnumMap<>(Quadrant.class);
        for (Map.Entry<Quadrant, List<String>> entry : quadrantGroups.entrySet()) {
            distribution.put(entry.getKey(),
                    totalAgents == 0 ? 0 : (double) entry.getValue().size() / totalAgents);
        }

        // 生成执行摘要
        String executiveSummary = generateExecutiveSummary(results, distribution);

        return new QuadrantMatrix(results, distribution, totalAgents,
                executiveSummary, Instant.now());
    }

    // ========== 维度分数计算 ==========

    /**
     * 计算价值分数（综合成功率、用户满意度等）
     */
    double calculateValueScore(List<AgentMetrics> history) {
        if (history.isEmpty()) return 0.0;

        // 取最近 N 条记录的加权平均
        int recentCount = Math.min(10, history.size());
        List<AgentMetrics> recent = history.subList(history.size() - recentCount, history.size());

        double successRateAvg = recent.stream()
                .mapToDouble(AgentMetrics::successRate).average().orElse(0);
        double userSatisfactionAvg = recent.stream()
                .mapToDouble(AgentMetrics::userSatisfaction).average().orElse(0);
        double taskCompletionRateAvg = recent.stream()
                .mapToDouble(AgentMetrics::taskCompletionRate).average().orElse(0);

        // 价值分数 = 成功率 * 0.4 + 用户满意度 * 0.35 + 完成率 * 0.25
        return successRateAvg * 0.4 + userSatisfactionAvg * 0.35 + taskCompletionRateAvg * 0.25;
    }

    /**
     * 计算成本分数（Token 消耗、API 调用成本等）
     */
    double calculateCostScore(List<AgentMetrics> history) {
        if (history.isEmpty()) return 1.0;

        int recentCount = Math.min(10, history.size());
        List<AgentMetrics> recent = history.subList(history.size() - recentCount, history.size());

        // 成本分数：归一化后的成本（0 = 最低成本，1 = 最高成本）
        // 这里我们将成本归一化到 [0, 1] 区间
        double avgCost = recent.stream()
                .mapToDouble(AgentMetrics::avgCostPerTask).average().orElse(0);
        double maxCost = recent.stream()
                .mapToDouble(AgentMetrics::maxCostPerTask).max().orElse(1);

        // 成本分 = avgCost / maxCost（越低越好）
        return maxCost == 0 ? 0 : Math.min(1.0, avgCost / maxCost);
    }

    /**
     * 计算质量分数
     */
    double calculateQualityScore(List<AgentMetrics> history) {
        if (history.isEmpty()) return 0.0;

        int recentCount = Math.min(10, history.size());
        List<AgentMetrics> recent = history.subList(history.size() - recentCount, history.size());

        double errorRateAvg = recent.stream()
                .mapToDouble(AgentMetrics::errorRate).average().orElse(0);
        double qualityScoreAvg = recent.stream()
                .mapToDouble(AgentMetrics::qualityScore).average().orElse(0);

        // 质量分 = 质量评分 * 0.7 + (1 - 错误率) * 0.3
        return qualityScoreAvg * 0.7 + (1 - errorRateAvg) * 0.3;
    }

    /**
     * 计算风险分数
     */
    double calculateRiskScore(List<AgentMetrics> history) {
        if (history.isEmpty()) return 0.0;

        int recentCount = Math.min(10, history.size());
        List<AgentMetrics> recent = history.subList(history.size() - recentCount, history.size());

        double securityIncidentRate = recent.stream()
                .mapToDouble(AgentMetrics::securityIncidentRate).average().orElse(0);
        double anomalyRate = recent.stream()
                .mapToDouble(AgentMetrics::anomalyRate).average().orElse(0);

        // 风险分 = 安全事件率 * 0.6 + 异常率 * 0.4
        return securityIncidentRate * 0.6 + anomalyRate * 0.4;
    }

    // ========== 象限判定 ==========

    /**
     * 根据价值和成本分数确定象限
     */
    Quadrant determineQuadrant(double valueScore, double costScore) {
        boolean highValue = valueScore >= thresholds.valueThreshold();
        boolean lowCost = costScore <= thresholds.costThreshold();

        if (highValue && lowCost) {
            return Quadrant.Q1_IDEAL;
        } else if (highValue && !lowCost) {
            return Quadrant.Q2_HIGH_VALUE_HIGH_COST;
        } else if (!highValue && lowCost) {
            return Quadrant.Q3_LOW_VALUE_LOW_COST;
        } else {
            return Quadrant.Q4_LOW_VALUE_HIGH_COST;
        }
    }

    // ========== 改进建议生成 ==========

    /**
     * 基于象限和各项指标生成改进建议
     */
    List<String> generateSuggestions(String agentId, Quadrant quadrant,
                                      double valueScore, double costScore,
                                      double qualityScore, double riskScore) {
        List<String> suggestions = new ArrayList<>();

        // 象限级建议
        switch (quadrant) {
            case Q1_IDEAL -> {
                suggestions.add("✅ 理想状态，继续保持当前策略");
                if (qualityScore < thresholds.qualityThreshold()) {
                    suggestions.add("⚠️ 质量分数略低，建议加强输出验证");
                }
                if (riskScore > thresholds.riskThreshold()) {
                    suggestions.add("⚠️ 风险分数偏高，建议加强安全监控");
                }
            }
            case Q2_HIGH_VALUE_HIGH_COST -> {
                suggestions.add("🔧 高价值但高成本，建议优化成本");
                suggestions.add("💡 建议：优化模型路由，使用更经济的模型处理简单任务");
                suggestions.add("💡 建议：启用上下文缓存，减少重复 Token 消耗");
                suggestions.add("💡 建议：对高频任务实施结果缓存");
                if (qualityScore < thresholds.qualityThreshold()) {
                    suggestions.add("⚠️ 同时关注质量优化，成本优化不应以牺牲质量为代价");
                }
            }
            case Q3_LOW_VALUE_LOW_COST -> {
                suggestions.add("📈 低成本但低价值，建议提升价值");
                suggestions.add("💡 建议：增加更多工具调用，扩展 Agent 能力范围");
                suggestions.add("💡 建议：优化 Prompt 策略，提升任务完成率");
                suggestions.add("💡 建议：引入更强大的模型以提升输出质量");
            }
            case Q4_LOW_VALUE_HIGH_COST -> {
                suggestions.add("🚨 低价值且高成本，需立即改进！");
                suggestions.add("💡 紧急建议：审查任务分配策略，确保 Agent 与任务匹配");
                suggestions.add("💡 紧急建议：考虑重新训练或调整 Agent 配置");
                suggestions.add("💡 紧急建议：如短期无法改善，考虑降级或下线该 Agent");
                if (riskScore > thresholds.riskThreshold()) {
                    suggestions.add("🚨 风险过高！建议立即进行安全审计");
                }
            }
            case EMPTY -> {
                suggestions.add("📝 暂无足够数据，建议录入更多指标");
            }
        }

        // 维度级微调建议
        if (qualityScore < 0.3) {
            suggestions.add("🔍 质量严重不足，建议增加验证层和人工审核");
        }
        if (riskScore > 0.7) {
            suggestions.add("🛡️ 风险严重，建议立即加强安全防护");
        }

        // 应用自定义规则
        for (EvaluationRule rule : customRules) {
            if (rule.matches(agentId, valueScore, costScore, qualityScore, riskScore)) {
                suggestions.add("📋 [自定义规则] " + rule.description());
            }
        }

        return suggestions;
    }

    /**
     * 生成执行摘要
     */
    String generateExecutiveSummary(Map<String, QuadrantResult> results,
                                     Map<Quadrant, Double> distribution) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Agent 系统执行摘要 ===\n\n");

        sb.append(String.format("总 Agent 数: %d\n\n", results.size()));

        for (Quadrant quadrant : Quadrant.values()) {
            if (quadrant == Quadrant.EMPTY) continue;
            double pct = distribution.getOrDefault(quadrant, 0.0) * 100;
            sb.append(String.format("  %s: %.1f%%\n", quadrant.label(), pct));
        }

        // 关键洞察
        long q4Count = results.values().stream()
                .filter(r -> r.quadrant() == Quadrant.Q4_LOW_VALUE_HIGH_COST)
                .count();
        long q1Count = results.values().stream()
                .filter(r -> r.quadrant() == Quadrant.Q1_IDEAL)
                .count();

        sb.append("\n关键洞察:\n");
        if (q4Count > 0) {
            sb.append(String.format("  🚨 %d 个 Agent 处于 Q4（低价值+高成本），需要立即关注\n", q4Count));
        }
        if (q1Count > 0) {
            sb.append(String.format("  ✅ %d 个 Agent 处于 Q1（理想状态）\n", q1Count));
        }
        long q2q3Count = results.size() - q1Count - q4Count;
        if (q2q3Count > 0) {
            sb.append(String.format("  ⚠️ %d 个 Agent 需要优化（Q2 或 Q3）\n", q2q3Count));
        }

        return sb.toString();
    }

    // ========== 自定义规则 ==========

    /**
     * 添加自定义评估规则
     */
    public void addRule(EvaluationRule rule) {
        customRules.add(rule);
        log.info("[管理者象限] 添加自定义规则: {}", rule.description());
    }

    private void initializeDefaultRules() {
        addRule(new EvaluationRule(
                "high_risk_critical",
                (agentId, v, c, q, r) -> r > 0.8,
                "风险分数超过 0.8，建议立即进行安全审查"
        ));

        addRule(new EvaluationRule(
                "low_value_sustained",
                (agentId, v, c, q, r) -> v < 0.2 && c > 0.3,
                "持续低价值+中高成本，建议重新评估 Agent 定位"
        ));
    }

    // ========== 配置方法 ==========

    /**
     * 设置权重配置
     */
    public void setWeights(double valueWeight, double costWeight,
                            double qualityWeight, double riskWeight) {
        this.weights = new WeightConfig(valueWeight, costWeight, qualityWeight, riskWeight);
    }

    /**
     * 设置阈值配置
     */
    public void setThresholds(double valueThreshold, double costThreshold,
                               double qualityThreshold, double riskThreshold) {
        this.thresholds = new ThresholdConfig(valueThreshold, costThreshold,
                qualityThreshold, riskThreshold);
    }

    /**
     * 获取权重配置
     */
    public WeightConfig getWeights() {
        return weights;
    }

    /**
     * 获取阈值配置
     */
    public ThresholdConfig getThresholds() {
        return thresholds;
    }

    // ========== 数据模型 ==========

    /**
     * Agent 运行指标
     */
    public record AgentMetrics(
            String agentId,
            double successRate,
            double userSatisfaction,
            double taskCompletionRate,
            double avgCostPerTask,
            double maxCostPerTask,
            double errorRate,
            double qualityScore,
            double securityIncidentRate,
            double anomalyRate,
            Instant timestamp
    ) {
        public AgentMetrics {
            agentId = Objects.requireNonNull(agentId, "agentId");
            timestamp = timestamp != null ? timestamp : Instant.now();
        }
    }

    /**
     * 四象限枚举
     */
    public enum Quadrant {
        /** Q1: 高价值 + 低成本 — 理想状态 */
        Q1_IDEAL("Q1 · 高价值+低成本（理想状态）"),
        /** Q2: 高价值 + 高成本 — 需要优化成本 */
        Q2_HIGH_VALUE_HIGH_COST("Q2 · 高价值+高成本（优化成本）"),
        /** Q3: 低价值 + 低成本 — 需要提升价值 */
        Q3_LOW_VALUE_LOW_COST("Q3 · 低价值+低成本（提升价值）"),
        /** Q4: 低价值 + 高成本 — 需要立即改进 */
        Q4_LOW_VALUE_HIGH_COST("Q4 · 低价值+高成本（立即改进）"),
        /** 无数据 */
        EMPTY("无数据");

        private final String label;

        Quadrant(String label) {
            this.label = label;
        }

        public String label() { return label; }
    }

    /**
     * 象限判定结果
     */
    public record QuadrantResult(
            String agentId,
            Quadrant quadrant,
            double valueScore,
            double costScore,
            double qualityScore,
            double riskScore,
            List<String> suggestions,
            Instant calculatedAt
    ) {
        public String summary() {
            return String.format("[%s] %s — value=%.2f cost=%.2f quality=%.2f risk=%.2f → %s",
                    agentId, quadrant.label(),
                    valueScore, costScore, qualityScore, riskScore,
                    suggestions.isEmpty() ? "无建议" : suggestions.get(0));
        }
    }

    /**
     * 四象限矩阵（全量结果）
     */
    public record QuadrantMatrix(
            Map<String, QuadrantResult> results,
            Map<Quadrant, Double> distribution,
            int totalAgents,
            String executiveSummary,
            Instant generatedAt
    ) {
        public List<QuadrantResult> getOrderedByPriority() {
            return results.values().stream()
                    .sorted(Comparator.comparingInt((QuadrantResult r) ->
                            switch (r.quadrant()) {
                                case Q4_LOW_VALUE_HIGH_COST -> 0;
                                case Q2_HIGH_VALUE_HIGH_COST -> 1;
                                case Q3_LOW_VALUE_LOW_COST -> 2;
                                case Q1_IDEAL -> 3;
                                case EMPTY -> 4;
                            })
                    ).toList();
        }
    }

    /**
     * 权重配置
     */
    public record WeightConfig(double valueWeight, double costWeight,
                                double qualityWeight, double riskWeight) {}

    /**
     * 阈值配置
     */
    public record ThresholdConfig(double valueThreshold, double costThreshold,
                                   double qualityThreshold, double riskThreshold) {}

    /**
     * 自定义评估规则
     */
    public record EvaluationRule(
            String name,
            RuleMatcher matcher,
            String description
    ) {
        /**
         * 判断规则是否匹配
         */
        public boolean matches(String agentId, double value, double cost,
                                double quality, double risk) {
            return matcher.test(agentId, value, cost, quality, risk);
        }
    }

    /**
     * 规则匹配函数式接口
     */
    @FunctionalInterface
    public interface RuleMatcher {
        boolean test(String agentId, double value, double cost,
                      double quality, double risk);
    }
}