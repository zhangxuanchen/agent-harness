package io.etclovg.codepilot.future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评估门控中间件：在关键节点实施评估门控
 * 对应书中 Ch19 —— 未来展望：持续评估与门控
 */
@Component
public class EvalGateMiddleware {

    private static final Logger log = LoggerFactory.getLogger(EvalGateMiddleware.class);

    private final Map<String, GateConfig> gateConfigs = new ConcurrentHashMap<>();
    private final Map<String, List<GateCheck>> gateHistory = new ConcurrentHashMap<>();

    public EvalGateMiddleware() {
        registerDefaultGates();
    }

    public GateResult checkGate(String gateId, String agentOutput, String taskContext) {
        GateConfig config = gateConfigs.get(gateId);
        if (config == null) {
            return new GateResult(gateId, false, "门控不存在", GateStatus.BYPASSED, 0.0, List.of(), Instant.now());
        }

        log.info("[EvalGateMiddleware] 门控检查: gateId={}, type={}", gateId, config.type());

        List<DimensionScore> scores = new ArrayList<>();

        for (GateDimension dimension : config.dimensions()) {
            double score = evaluateDimension(dimension, agentOutput, taskContext);
            scores.add(new DimensionScore(dimension.name(), score,
                    score >= dimension.threshold() ? "通过" : "未通过"));
        }

        double overallScore = scores.stream()
                .mapToDouble(DimensionScore::score)
                .average()
                .orElse(0.0);

        GateStatus status = overallScore >= config.passThreshold()
                ? GateStatus.PASSED : GateStatus.BLOCKED;

        GateResult result = new GateResult(
                gateId, status == GateStatus.PASSED,
                status == GateStatus.PASSED ? "门控通过" : "门控未通过",
                status, overallScore, scores, Instant.now()
        );

        gateHistory.computeIfAbsent(gateId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new GateCheck(gateId, overallScore, status, Instant.now()));

        log.info("[EvalGateMiddleware] 门控结果: gateId={}, status={}, score={}",
                gateId, status, String.format("%.2f", overallScore));

        return result;
    }

    public void registerGate(GateConfig config) {
        gateConfigs.put(config.id(), config);
        log.info("[EvalGateMiddleware] 注册门控: id={}, name={}, type={}",
                config.id(), config.name(), config.type());
    }

    public Optional<GateConfig> getGate(String gateId) {
        return Optional.ofNullable(gateConfigs.get(gateId));
    }

    public List<GateCheck> getGateHistory(String gateId) {
        return gateHistory.getOrDefault(gateId, List.of());
    }

    private double evaluateDimension(GateDimension dimension, String output, String context) {
        return switch (dimension.evaluationType()) {
            case LENGTH -> {
                if (output == null) yield 0.0;
                double ratio = (double) output.length() / dimension.targetValue();
                yield Math.min(1.0, ratio);
            }
            case KEYWORD -> {
                if (output == null || dimension.keywords() == null) yield 0.0;
                long matches = dimension.keywords().stream()
                        .filter(k -> output.toLowerCase().contains(k.toLowerCase()))
                        .count();
                yield dimension.keywords().isEmpty() ? 0.0 :
                        (double) matches / dimension.keywords().size();
            }
            case FORMAT -> {
                if (output == null) yield 0.0;
                yield output.trim().startsWith("{") || output.trim().startsWith("[") ? 1.0 : 0.5;
            }
            case CONTEXT_RELEVANCE -> {
                if (output == null || context == null) yield 0.0;
                Set<String> contextWords = Set.of(context.toLowerCase().split("\\s+"));
                long matches = contextWords.stream()
                        .filter(w -> w.length() > 2 && output.toLowerCase().contains(w))
                        .count();
                yield contextWords.isEmpty() ? 0.5 : (double) matches / contextWords.size();
            }
        };
    }

    private void registerDefaultGates() {
        registerGate(new GateConfig("pre-deploy", "部署前评估",
                GateType.DEPLOYMENT, 0.85,
                List.of(
                        new GateDimension("输出长度", GateDimension.EvaluationType.LENGTH,
                                500.0, 0.7, List.of()),
                        new GateDimension("关键字覆盖", GateDimension.EvaluationType.KEYWORD,
                                0.0, 0.6, List.of("功能", "测试", "验证")),
                        new GateDimension("格式合规", GateDimension.EvaluationType.FORMAT,
                                0.0, 0.8, List.of())
                )));

        registerGate(new GateConfig("post-execution", "执行后评估",
                GateType.RUNTIME, 0.75,
                List.of(
                        new GateDimension("输出长度", GateDimension.EvaluationType.LENGTH,
                                100.0, 0.5, List.of()),
                        new GateDimension("上下文关联", GateDimension.EvaluationType.CONTEXT_RELEVANCE,
                                0.0, 0.5, List.of())
                )));
    }

    public record GateConfig(
            String id, String name, GateType type,
            double passThreshold, List<GateDimension> dimensions
    ) {}

    public record GateDimension(
            String name, EvaluationType evaluationType,
            double targetValue, double threshold, List<String> keywords
    ) {
        public enum EvaluationType {
            LENGTH, KEYWORD, FORMAT, CONTEXT_RELEVANCE
        }
    }

    public record GateResult(
            String gateId, boolean passed, String message,
            GateStatus status, double overallScore,
            List<DimensionScore> dimensions, Instant timestamp
    ) {}

    public record DimensionScore(String name, double score, String detail) {}

    public record GateCheck(
            String gateId, double score, GateStatus status, Instant timestamp
    ) {}

    public enum GateType {
            DEPLOYMENT, RUNTIME, ADMISSION, RELEASE
    }

    public enum GateStatus {
            PASSED, BLOCKED, BYPASSED
    }
}