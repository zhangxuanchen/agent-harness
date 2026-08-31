package io.etclovg.codepilot.foundation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评估中间件：在 Agent 执行链路中插入评估节点
 * 对应书中 Ch01 §1.3 —— Agent 评估体系
 */
@Component
public class EvaluatorMiddleware {

    private static final Logger log = LoggerFactory.getLogger(EvaluatorMiddleware.class);

    private final Map<String, EvaluationResult> evaluationHistory = new ConcurrentHashMap<>();
    private final List<Evaluator> evaluators = new ArrayList<>();

    public EvaluatorMiddleware() {
        registerDefaultEvaluators();
    }

    public EvaluationResult evaluate(String task, String output) {
        log.info("[EvaluatorMiddleware] 开始评估: task={}, outputLength={}",
                task, output != null ? output.length() : 0);

        List<DimensionScore> scores = new ArrayList<>();
        for (Evaluator evaluator : evaluators) {
            try {
                DimensionScore score = evaluator.evaluate(task, output);
                scores.add(score);
                log.debug("[EvaluatorMiddleware] 维度: {}, score={}",
                        evaluator.getName(), score.score());
            } catch (Exception e) {
                log.warn("[EvaluatorMiddleware] 评估器异常: {}, error={}",
                        evaluator.getName(), e.getMessage());
                scores.add(new DimensionScore(evaluator.getName(), 0.0, e.getMessage()));
            }
        }

        double overallScore = scores.stream()
                .mapToDouble(DimensionScore::score)
                .average()
                .orElse(0.0);

        EvaluationResult result = new EvaluationResult(
                UUID.randomUUID().toString().substring(0, 8),
                task, output, overallScore, scores,
                Instant.now()
        );

        evaluationHistory.put(result.id(), result);
        log.info("[EvaluatorMiddleware] 评估完成: overallScore={}, dimensions={}",
                String.format("%.2f", overallScore), scores.size());

        return result;
    }

    public void registerEvaluator(Evaluator evaluator) {
        evaluators.add(evaluator);
        log.info("[EvaluatorMiddleware] 注册评估器: {}", evaluator.getName());
    }

    public EvaluationResult getEvaluation(String id) {
        return evaluationHistory.get(id);
    }

    public List<EvaluationResult> getRecentEvaluations(int limit) {
        return evaluationHistory.values().stream()
                .sorted(Comparator.comparing(EvaluationResult::timestamp).reversed())
                .limit(limit)
                .toList();
    }

    private void registerDefaultEvaluators() {
        evaluators.add(new CorrectnessEvaluator());
        evaluators.add(new CompletenessEvaluator());
        evaluators.add(new RelevanceEvaluator());
    }

    public interface Evaluator {
        String getName();
        DimensionScore evaluate(String task, String output);
    }

    private static class CorrectnessEvaluator implements Evaluator {
        @Override
        public String getName() { return "正确性"; }

        @Override
        public DimensionScore evaluate(String task, String output) {
            double score = output != null && !output.isBlank() ? 0.8 : 0.0;
            return new DimensionScore(getName(), score, "基础正确性评估");
        }
    }

    private static class CompletenessEvaluator implements Evaluator {
        @Override
        public String getName() { return "完整性"; }

        @Override
        public DimensionScore evaluate(String task, String output) {
            double score = output != null && output.length() > 50 ? 0.7 : 0.3;
            return new DimensionScore(getName(), score, "输出完整性评估");
        }
    }

    private static class RelevanceEvaluator implements Evaluator {
        @Override
        public String getName() { return "相关性"; }

        @Override
        public DimensionScore evaluate(String task, String output) {
            if (task == null || output == null) {
                return new DimensionScore(getName(), 0.0, "无法评估");
            }
            Set<String> taskWords = Set.of(task.toLowerCase().split("\\s+"));
            long matches = taskWords.stream()
                    .filter(w -> w.length() > 1 && output.toLowerCase().contains(w))
                    .count();
            double score = taskWords.isEmpty() ? 0.5 : (double) matches / taskWords.size();
            return new DimensionScore(getName(), score, "任务相关性评估");
        }
    }

    public record DimensionScore(String dimension, double score, String detail) {}

    public record EvaluationResult(
            String id, String task, String output,
            double overallScore, List<DimensionScore> dimensionScores,
            Instant timestamp
    ) {
        public boolean isPassing(double threshold) {
            return overallScore >= threshold;
        }
    }
}