package io.etclovg.codepilot.casestudy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评估中间件：案例研究中的评估流程集成
 * 对应书中 Ch18 —— Agent 评估实战案例
 */
@Component
public class EvaluationMiddleware {

    private static final Logger log = LoggerFactory.getLogger(EvaluationMiddleware.class);

    private final Map<String, CaseEvaluation> evaluations = new ConcurrentHashMap<>();

    public CaseEvaluation evaluateCase(String caseId, String caseType,
                                       String input, String expected, String actual) {
        log.info("[EvaluationMiddleware] 开始案例评估: caseId={}, type={}", caseId, caseType);

        String evalId = "eval-" + UUID.randomUUID().toString().substring(0, 8);
        double score = calculateScore(expected, actual);
        List<EvaluationDimension> dimensions = calculateDimensions(expected, actual);

        CaseEvaluation evaluation = new CaseEvaluation(
                evalId, caseId, caseType, input, expected, actual,
                score, dimensions,
                score >= 0.8 ? EvaluationStatus.PASS : EvaluationStatus.FAIL,
                Instant.now()
        );

        evaluations.put(evalId, evaluation);
        log.info("[EvaluationMiddleware] 案例评估完成: evalId={}, score={}, status={}",
                evalId, String.format("%.2f", score), evaluation.status());

        return evaluation;
    }

    public EvaluationSummary summarizeByCaseType(String caseType) {
        List<CaseEvaluation> caseEvaluations = evaluations.values().stream()
                .filter(e -> e.caseType().equals(caseType))
                .toList();

        long passCount = caseEvaluations.stream()
                .filter(e -> e.status() == EvaluationStatus.PASS).count();
        double passRate = caseEvaluations.isEmpty() ? 0.0 :
                (double) passCount / caseEvaluations.size();

        return new EvaluationSummary(caseType, caseEvaluations.size(),
                passCount, passRate);
    }

    public Optional<CaseEvaluation> getEvaluation(String evalId) {
        return Optional.ofNullable(evaluations.get(evalId));
    }

    public List<CaseEvaluation> getRecentEvaluations(int limit) {
        return evaluations.values().stream()
                .sorted(Comparator.comparing(CaseEvaluation::evaluatedAt).reversed())
                .limit(limit)
                .toList();
    }

    private double calculateScore(String expected, String actual) {
        if (expected == null || actual == null) return 0.0;
        double similarity = computeSimilarity(expected.toLowerCase(), actual.toLowerCase());
        return Math.min(1.0, similarity * 1.2);
    }

    private List<EvaluationDimension> calculateDimensions(String expected, String actual) {
        List<EvaluationDimension> dimensions = new ArrayList<>();

        dimensions.add(new EvaluationDimension("格式匹配",
                checkFormatMatch(expected, actual) ? 1.0 : 0.5,
                checkFormatMatch(expected, actual) ? "格式正确" : "格式不匹配"));

        dimensions.add(new EvaluationDimension("内容覆盖",
                computeSimilarity(expected, actual),
                "内容相似度评估"));

        dimensions.add(new EvaluationDimension("关键字匹配",
                computeKeywordOverlap(expected, actual),
                "关键字匹配度评估"));

        return dimensions;
    }

    private boolean checkFormatMatch(String expected, String actual) {
        if (expected.trim().startsWith("{") && actual.trim().startsWith("{")) return true;
        if (expected.contains("\n") && actual.contains("\n")) return true;
        return expected.length() > 0 && actual.length() > 0;
    }

    private double computeSimilarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return 0.0;
        Set<String> wordsA = Set.of(a.split("\\s+"));
        Set<String> wordsB = Set.of(b.split("\\s+"));
        long intersection = wordsA.stream().filter(wordsB::contains).count();
        long union = wordsA.size() + wordsB.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private double computeKeywordOverlap(String expected, String actual) {
        if (expected == null || actual == null) return 0.0;
        Set<String> keywords = new HashSet<>(List.of(
                expected.toLowerCase().split("[\\s,，。！？?！.。]+")));
        keywords.removeIf(String::isBlank);
        if (keywords.isEmpty()) return 0.5;

        long matches = keywords.stream()
                .filter(k -> k.length() > 1 && actual.toLowerCase().contains(k))
                .count();
        return (double) matches / keywords.size();
    }

    public record CaseEvaluation(
            String evalId, String caseId, String caseType,
            String input, String expected, String actual,
            double score, List<EvaluationDimension> dimensions,
            EvaluationStatus status, Instant evaluatedAt
    ) {}

    public record EvaluationDimension(
            String name, double score, String detail
    ) {}

    public record EvaluationSummary(
            String caseType, int total, long passed, double passRate
    ) {}

    public enum EvaluationStatus {
        PASS, FAIL, REVIEW
    }
}