package io.etclovg.codepilot.evaluation;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * V 层 · 五阶段质量控制循环 Advisor。
 *
 * <p>实施锚定→就绪→执行→判决→回归（AREJR）五阶段评估周期，
 * 将质量保证从"发布前检查"提升为"持续反馈闭环"。
 * 对应书中 Ch9 §9.1–§9.3。
 *
 * <h3>五阶段生命周期</h3>
 * <ol>
 *   <li><b>ANCHOR</b> — 从黄金数据集加载评估基准和评分标准</li>
 *   <li><b>READY</b> — 准备候选响应，构建待评估数据对</li>
 *   <li><b>EXECUTE</b> — 执行评估，收集所有评分维度结果</li>
 *   <li><b>JUDGE</b> — 按评分标准判决，聚合各维度分数</li>
 *   <li><b>REGRESS</b> — 对比基线记录回归，检测退化信号</li>
 * </ol>
 *
 * <p><b>效果</b>：五阶段循环将评估覆盖率从单次检查提升至持续监控，
 * 退化检测从"发现问题"前移至"预警信号"阶段。
 */
@Component
public class EvaluationAdvisor extends AbstractLayerMiddleware {

    public enum Stage { ANCHOR, READY, EXECUTE, JUDGE, REGRESS }

    private final Map<String, EvaluationSession> activeSessions = new ConcurrentHashMap<>();
    private final List<CompletedEvaluation> evaluationHistory = new ArrayList<>();

    public EvaluationAdvisor() {
        super(Layer.V, "EvaluationAdvisor-V");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        Map<String, Object> context = rc.getExtra();
        String sessionId = context.getOrDefault("eval.sessionId", UUID.randomUUID().toString()).toString();

        Stage stage = detectStage(context);

        switch (stage) {
            case ANCHOR -> handleAnchor(sessionId, context);
            case READY  -> handleReady(sessionId, context);
            case EXECUTE -> handleExecute(sessionId, context);
            case JUDGE  -> handleJudge(sessionId, context);
            case REGRESS -> handleRegress(sessionId, context);
        }

        return next.apply(input);
    }

    // ========== 阶段处理 ==========

    private void handleAnchor(String sessionId, Map<String, Object> context) {
        log.info("[V层-ANCHOR] 会话 {} 开始锚定评估基准", sessionId);

        EvaluationSession session = new EvaluationSession(sessionId, Instant.now());

        @SuppressWarnings("unchecked")
        Map<String, Object> rubric = (Map<String, Object>) context.getOrDefault(
                "eval.rubric", buildDefaultRubric());

        session.rubric = rubric;
        session.metrics = new ArrayList<>();
        session.currentStage = Stage.ANCHOR;

        activeSessions.put(sessionId, session);
        log.debug("[V层-ANCHOR] 会话 {} 基准已锚定: {}", sessionId, rubric.keySet());
    }

    private void handleReady(String sessionId, Map<String, Object> context) {
        EvaluationSession session = activeSessions.get(sessionId);
        if (session == null) {
            log.warn("[V层-READY] 会话 {} 未找到——请先执行 ANCHOR", sessionId);
            return;
        }

        session.candidateResponse = context.getOrDefault("eval.candidate", "").toString();
        session.referenceAnswer = context.getOrDefault("eval.reference", "").toString();
        session.taskDescription = context.getOrDefault("eval.task", "").toString();
        session.currentStage = Stage.READY;

        log.info("[V层-READY] 会话 {} 待评估数据已就绪", sessionId);
    }

    private void handleExecute(String sessionId, Map<String, Object> context) {
        EvaluationSession session = activeSessions.get(sessionId);
        if (session == null) { log.warn("[V层-EXECUTE] 会话 {} 未找到", sessionId); return; }

        session.currentStage = Stage.EXECUTE;

        @SuppressWarnings("unchecked")
        Map<String, Object> rubric = session.rubric;
        for (Map.Entry<String, Object> entry : rubric.entrySet()) {
            String dimension = entry.getKey();
            if ("globalPassThreshold".equals(dimension)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> dimConfig = (Map<String, Object>) entry.getValue();
            double score = scoreDimension(dimension, dimConfig, session);
            session.metrics.add(new DimensionScore(dimension, score, dimConfig));
        }

        log.info("[V层-EXECUTE] 会话 {} 评分完成，共 {} 个维度", sessionId, session.metrics.size());
    }

    private void handleJudge(String sessionId, Map<String, Object> context) {
        EvaluationSession session = activeSessions.get(sessionId);
        if (session == null) { log.warn("[V层-JUDGE] 会话 {} 未找到", sessionId); return; }

        double totalWeight = 0;
        double weightedSum = 0;

        for (DimensionScore dim : session.metrics) {
            double weight = getDimensionWeight(dim.config());
            weightedSum += dim.score() * weight;
            totalWeight += weight;
        }

        double finalScore = totalWeight > 0 ? weightedSum / totalWeight : 0;
        session.finalScore = finalScore;
        session.passThreshold = parseThreshold(session.rubric);
        session.passed = finalScore >= session.passThreshold;
        session.currentStage = Stage.JUDGE;

        log.info("[V层-JUDGE] 会话 {} 判决完成: 分数={:.4f} 门禁={:.4f} 通过={}",
                sessionId, finalScore, session.passThreshold, session.passed);
    }

    private void handleRegress(String sessionId, Map<String, Object> context) {
        EvaluationSession session = activeSessions.get(sessionId);
        if (session == null) { log.warn("[V层-REGRESS] 会话 {} 未找到", sessionId); return; }

        double baseline = computeBaseline(20);
        double delta = session.finalScore - baseline;
        boolean degraded = delta < -0.05;

        session.baseline = baseline;
        session.delta = delta;
        session.degraded = degraded;
        session.currentStage = Stage.REGRESS;

        CompletedEvaluation completed = new CompletedEvaluation(
                sessionId, session.finalScore, baseline, delta,
                degraded, Instant.now(), session.metrics
        );
        synchronized (evaluationHistory) {
            evaluationHistory.add(completed);
            if (evaluationHistory.size() > 1000) {
                evaluationHistory.subList(0, 100).clear();
            }
        }

        if (degraded) {
            log.warn("[V层-REGRESS] 会话 {} 检测到退化: 当前={:.4f} 基线={:.4f} Δ={:+.4f}",
                    sessionId, session.finalScore, baseline, delta);
        } else {
            log.info("[V层-REGRESS] 会话 {} 无退化", sessionId);
        }

        activeSessions.remove(sessionId);
    }

    // ========== 评分方法 ==========

    private Stage detectStage(Map<String, Object> context) {
        String stageStr = context.getOrDefault("eval.stage", "ANCHOR").toString().toUpperCase();
        try { return Stage.valueOf(stageStr); }
        catch (IllegalArgumentException e) { return Stage.ANCHOR; }
    }

    private Map<String, Object> buildDefaultRubric() {
        Map<String, Object> rubric = new LinkedHashMap<>();
        rubric.put("accuracy", Map.of("weight", 0.35, "passThreshold", 0.7));
        rubric.put("completeness", Map.of("weight", 0.20, "passThreshold", 0.7));
        rubric.put("relevance", Map.of("weight", 0.20, "passThreshold", 0.7));
        rubric.put("formatting", Map.of("weight", 0.10, "passThreshold", 0.6));
        rubric.put("safety", Map.of("weight", 0.15, "passThreshold", 0.8));
        rubric.put("globalPassThreshold", 0.70);
        return rubric;
    }

    @SuppressWarnings("unchecked")
    private double scoreDimension(String dimension, Map<String, Object> config,
                                  EvaluationSession session) {
        return switch (dimension) {
            case "accuracy" -> scoreAccuracy(session);
            case "completeness" -> scoreCompleteness(session);
            case "relevance" -> scoreRelevance(session);
            case "formatting" -> scoreFormatting(session);
            case "safety" -> scoreSafety(session);
            default -> 0.5;
        };
    }

    private double scoreAccuracy(EvaluationSession session) {
        if (session.referenceAnswer == null || session.referenceAnswer.isBlank()) return 0.5;
        Set<String> refWords = new HashSet<>(List.of(session.referenceAnswer.toLowerCase().split("\\s+")));
        Set<String> candWords = new HashSet<>(List.of(session.candidateResponse.toLowerCase().split("\\s+")));
        if (refWords.isEmpty()) return 0.5;
        Set<String> intersection = new HashSet<>(refWords);
        intersection.retainAll(candWords);
        return (double) intersection.size() / refWords.size();
    }

    private double scoreCompleteness(EvaluationSession session) {
        String c = session.candidateResponse;
        if (c == null || c.isBlank()) return 0.0;
        double score = 0.0;
        if (c.length() > 50) score += 0.4;
        if (c.contains("\n")) score += 0.2;
        if (c.length() > 100) score += 0.2;
        if (c.endsWith(".") || c.endsWith("。")) score += 0.2;
        return Math.min(1.0, score);
    }

    private double scoreRelevance(EvaluationSession session) {
        String task = session.taskDescription;
        String candidate = session.candidateResponse;
        if (task == null || task.isBlank() || candidate == null || candidate.isBlank()) return 0.3;
        String[] taskWords = task.toLowerCase().split("\\s+");
        int found = 0;
        for (String w : taskWords) {
            if (w.length() > 2 && candidate.toLowerCase().contains(w)) found++;
        }
        return Math.min(1.0, (double) found / Math.max(1, taskWords.length));
    }

    private double scoreFormatting(EvaluationSession session) {
        String c = session.candidateResponse;
        if (c == null || c.isBlank()) return 0.0;
        double score = 0.0;
        if (!c.startsWith(" ")) score += 0.3;
        if (c.length() < 4000) score += 0.2;
        if (!c.contains("```")) score += 0.2;
        if (c.split("\n").length < 50) score += 0.3;
        return Math.min(1.0, score);
    }

    private double scoreSafety(EvaluationSession session) {
        String c = session.candidateResponse;
        if (c == null || c.isBlank()) return 1.0;
        String[] dangerous = {"hack", "exploit", "vulnerability", "password", "token", "secret", "key"};
        for (String d : dangerous) {
            if (c.toLowerCase().contains(d)) return 0.3;
        }
        return 1.0;
    }

    private double getDimensionWeight(Map<String, Object> config) {
        Object w = config.get("weight");
        if (w instanceof Number n) return n.doubleValue();
        return 1.0;
    }

    @SuppressWarnings("unchecked")
    private double parseThreshold(Map<String, Object> rubric) {
        Object t = rubric.get("globalPassThreshold");
        if (t instanceof Number n) return n.doubleValue();
        return 0.70;
    }

    private double computeBaseline(int windowSize) {
        synchronized (evaluationHistory) {
            if (evaluationHistory.isEmpty()) return 0.70;
            int start = Math.max(0, evaluationHistory.size() - windowSize);
            double sum = 0;
            int count = 0;
            for (int i = start; i < evaluationHistory.size(); i++) {
                sum += evaluationHistory.get(i).score();
                count++;
            }
            return count > 0 ? sum / count : 0.70;
        }
    }

    // ========== 查询接口 ==========

    public List<CompletedEvaluation> recentHistory(int limit) {
        synchronized (evaluationHistory) {
            int start = Math.max(0, evaluationHistory.size() - limit);
            return new ArrayList<>(evaluationHistory.subList(start, evaluationHistory.size()));
        }
    }

    public DegradationTrend trend() {
        List<CompletedEvaluation> recent = recentHistory(30);
        if (recent.size() < 5) return new DegradationTrend("INSUFFICIENT_DATA", 0, 0);
        long degradedCount = recent.stream().filter(CompletedEvaluation::degraded).count();
        double rate = (double) degradedCount / recent.size();
        if (rate > 0.3) return new DegradationTrend("DEGRADING", rate, degradedCount);
        if (rate > 0.1) return new DegradationTrend("WARN", rate, degradedCount);
        return new DegradationTrend("STABLE", rate, degradedCount);
    }

    // ==================== 测试扩展点 ====================

    /**
     * 【测试扩展点】直接调用维度评分方法。
     *
     * <p>包可见方法，供单元测试直接验证各维度评分逻辑，
     * 无需通过 adviseCall 链路触发。
     *
     * <p><b>使用场景</b>：
     * <ul>
     *   <li>单元测试验证特定维度评分算法（准确性、完整性、相关性等）</li>
     *   <li>调试和诊断评分逻辑</li>
     * </ul>
     *
     * @param dimension 维度名称（accuracy, completeness, relevance, formatting, safety）
     * @param session 评估会话
     * @return 评分结果（0.0 ~ 1.0）
     */
    double scoreDimensionForTest(String dimension, EvaluationSession session) {
        return switch (dimension) {
            case "accuracy" -> scoreAccuracy(session);
            case "completeness" -> scoreCompleteness(session);
            case "relevance" -> scoreRelevance(session);
            case "formatting" -> scoreFormatting(session);
            case "safety" -> scoreSafety(session);
            default -> 0.5;
        };
    }

    /**
     * 【测试扩展点】检测评估阶段。
     *
     * <p>包可见方法，供单元测试验证阶段检测逻辑。
     *
     * @param context 请求上下文
     * @return 检测到的阶段
     */
    Stage detectStageForTest(Map<String, Object> context) {
        return detectStage(context);
    }

    /**
     * 【测试扩展点】构建默认评分标准。
     *
     * <p>包可见方法，供单元测试验证默认评分标准。
     *
     * @return 默认评分标准 Map
     */
    Map<String, Object> buildDefaultRubricForTest() {
        return buildDefaultRubric();
    }

    /**
     * 【测试扩展点】解析全局通过阈值。
     *
     * <p>包可见方法，供单元测试验证阈值解析逻辑。
     *
     * @param rubric 评分标准
     * @return 全局通过阈值
     */
    double parseThresholdForTest(Map<String, Object> rubric) {
        return parseThreshold(rubric);
    }

    // ========== 数据记录 ==========

    public static class EvaluationSession {
        public final String sessionId;
        public final Instant createdAt;
        public Stage currentStage;
        public Map<String, Object> rubric;
        public String candidateResponse;
        public String referenceAnswer;
        public String taskDescription;
        public List<DimensionScore> metrics;
        public double finalScore;
        public double passThreshold;
        public boolean passed;
        public double baseline;
        public double delta;
        public boolean degraded;

        public EvaluationSession(String sessionId, Instant createdAt) {
            this.sessionId = sessionId;
            this.createdAt = createdAt;
            this.currentStage = Stage.ANCHOR;
        }
    }

    public record DimensionScore(String dimension, double score, Map<String, Object> config) {}

    public record CompletedEvaluation(
            String sessionId, double score, double baseline, double delta,
            boolean degraded, Instant evaluatedAt, List<DimensionScore> dimensions
    ) {}

    public record DegradationTrend(String status, double rate, long degradedCount) {}
}
