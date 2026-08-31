package io.etclovg.codepilot.observability;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * O 层 · 假设漂移检测器。
 *
 * <p>持续追踪每个 Advisor 的隐含假设，检测假设是否过期或失效。
 * 对应书中 Ch8 §8.5 假设漂移检测设计。
 *
 * <p><b>核心功能</b>：
 * <ul>
 *   <li><b>假设追踪</b>：持续追踪每个 Advisor 的隐含假设</li>
 *   <li><b>漂移检测</b>：检测假设是否过期或失效（如沙箱隔离率下降）</li>
 *   <li><b>报告生成</b>：提供漂移检测报告和建议</li>
 *   <li><b>告警通知</b>：当假设漂移超过阈值时触发告警</li>
 * </ul>
 *
 * <p><b>漂移类型</b>：
 * <ul>
 *   <li><b>突然漂移</b>：假设突然失效（如成功率从 99% 降到 50%）</li>
 *   <li><b>渐进漂移</b>：假设逐渐失效（如平均延迟缓慢上升）</li>
 *   <li><b>周期性漂移</b>：假设周期性失效（如高峰期性能下降）</li>
 *   <li><b>上下文漂移</b>：假设在某些上下文中失效（如特定任务类型）</li>
 * </ul>
 *
 * <p><b>与 HarnessAssumptionRegistry 的关系</b>：
 * <ul>
 *   <li>HarnessAssumptionRegistry 负责假设的注册和管理</li>
 *   <li>AssumptionDriftDetector 负责假设的验证和漂移检测</li>
 *   <li>两者协同实现"Harness 即假设"理念</li>
 * </ul>
 *
 * <p><b>与书中内容对应</b>：
 * <ul>
 *   <li>§8.5.1 假设追踪机制</li>
 *   <li>§8.5.2 漂移检测算法</li>
 *   <li>§8.5.3 漂移报告与告警</li>
 * </ul>
 *
 * <p><b>页面参考</b>：Ch8 §8.5 假设漂移检测
 */
@Component
public class AssumptionDriftDetector extends AbstractLayerMiddleware {

    private final HarnessAssumptionRegistry assumptionRegistry;

    // ==================== 漂移检测配置 ====================

    /** 突然漂移阈值（单次验证偏差） */
    private double suddenDriftThreshold = 0.3; // 30%
    /** 渐进漂移阈值（连续N次验证偏差） */
    private double gradualDriftThreshold = 0.15; // 15%
    /** 渐进漂移最小样本数 */
    private int gradualDriftMinSamples = 10;
    /** 假设过期时间（默认 7 天） */
    private Duration assumptionExpiryTime = Duration.ofDays(7);

    /** 漂移告警处理器 */
    private final List<DriftAlertHandler> driftAlertHandlers = new ArrayList<>();

    // ==================== 运行时数据 ====================

    /** 假设ID → 最近验证值序列（用于渐进漂移检测） */
    private final Map<String, Deque<Double>> assumptionValueHistory = new ConcurrentHashMap<>();
    /** 假设ID → 漂移状态 */
    private final Map<String, DriftStatus> driftStatusMap = new ConcurrentHashMap<>();
    /** 假设ID → 最后验证时间 */
    private final Map<String, Instant> lastValidationTime = new ConcurrentHashMap<>();

    public AssumptionDriftDetector(HarnessAssumptionRegistry assumptionRegistry) {
        super(Layer.O, "AssumptionDriftDetector");
        this.assumptionRegistry = assumptionRegistry;
        log.info("[O层·漂移检测] AssumptionDriftDetector 已初始化，集成 HarnessAssumptionRegistry");
    }

    /**
     * 核心拦截方法：在模型调用后检测假设漂移。
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        // 执行实际调用，完成后检测假设漂移
        return next.apply(input)
                .doOnComplete(() -> {
                    // 提取上下文信息
                    Map<String, Object> context = rc.getExtra();
                    String taskId = extractTaskId(context);

                    // 获取当前活跃的假设并进行验证
                    List<HarnessAssumptionRegistry.Assumption> assumptions =
                            assumptionRegistry.getAllActiveAssumptions();

                    for (HarnessAssumptionRegistry.Assumption assumption : assumptions) {
                        // 从 context 中提取实际观测值
                        Double actualValue = extractActualValue(context, assumption);

                        if (actualValue != null) {
                            // 记录验证结果
                            boolean valid = validateAssumption(assumption, actualValue);

                            // 检测漂移
                            DriftResult drift = detectDrift(assumption, actualValue);

                            // 处理漂移告警
                            if (drift != null && drift.hasDrift()) {
                                handleDrift(assumption, drift, taskId);
                            }

                            log.trace("[O层·漂移检测] 假设 {} 验证: 实际值={}, 阈值={}, 有效={}",
                                    assumption.id(), actualValue, assumption.threshold(), valid);
                        }
                    }
                });
    }

    // ==================== 假设验证方法 ====================

    /**
     * 验证假设是否满足阈值。
     */
    private boolean validateAssumption(HarnessAssumptionRegistry.Assumption assumption, double actualValue) {
        boolean valid = actualValue >= assumption.threshold();

        // 记录到假设注册表
        assumptionRegistry.recordValidation(assumption.id(), actualValue, valid);

        // 更新验证时间
        lastValidationTime.put(assumption.id(), Instant.now());

        // 记录到值历史（用于渐进漂移检测）
        Deque<Double> history = assumptionValueHistory.computeIfAbsent(
                assumption.id(), k -> new ArrayDeque<>()
        );
        history.addLast(actualValue);

        // 保留最近 100 个值
        while (history.size() > 100) {
            history.removeFirst();
        }

        return valid;
    }

    /**
     * 从 context 中提取实际观测值。
     */
    private Double extractActualValue(Map<String, Object> context,
                                      HarnessAssumptionRegistry.Assumption assumption) {
        // 根据假设类型提取不同的观测值
        return switch (assumption.type()) {
            case SECURITY -> {
                // 安全假设：从 context 提取安全指标
                // 例如：沙箱隔离率、输入验证通过率等
                yield extractDoubleFromContext(context, "security.isolation_rate");
            }
            case PERFORMANCE -> {
                // 性能假设：从 context 提取性能指标
                // 例如：平均延迟、吞吐量等
                yield extractDoubleFromContext(context, "performance.latency_ms");
            }
            case RELIABILITY -> {
                // 可靠性假设：从 context 提取可靠性指标
                // 例如：成功率、错误率等
                yield extractDoubleFromContext(context, "reliability.success_rate");
            }
            case COST -> {
                // 成本假设：从 context 提取成本指标
                // 例如：单次调用成本、token 消耗等
                yield extractDoubleFromContext(context, "cost.per_call");
            }
        };
    }

    /**
     * 从 context 中安全提取 Double 值。
     */
    private Double extractDoubleFromContext(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 漂移检测方法 ====================

    /**
     * 检测假设漂移。
     *
     * <p>对应书中 Ch8 §8.5.2 漂移检测算法。
     */
    private DriftResult detectDrift(HarnessAssumptionRegistry.Assumption assumption, double actualValue) {
        String assumptionId = assumption.id();
        double threshold = assumption.threshold();

        // 1. 检测突然漂移（Sudden Drift）
        double deviation = Math.abs(actualValue - threshold) / threshold;
        if (deviation > suddenDriftThreshold) {
            DriftStatus previousStatus = driftStatusMap.get(assumptionId);
            driftStatusMap.put(assumptionId, DriftStatus.SUDDEN_DRIFT);

            return new DriftResult(
                    assumptionId,
                    DriftType.SUDDEN,
                    actualValue,
                    threshold,
                    deviation,
                    Instant.now(),
                    String.format("突然漂移：实际值 %.2f 偏离阈值 %.2f 达 %.1f%%",
                            actualValue, threshold, deviation * 100),
                    generateDriftRecommendation(assumption, DriftType.SUDDEN)
            );
        }

        // 2. 检测渐进漂移（Gradual Drift）
        Deque<Double> history = assumptionValueHistory.get(assumptionId);
        if (history != null && history.size() >= gradualDriftMinSamples) {
            double avgDeviation = calculateAverageDeviation(history, threshold);

            if (avgDeviation > gradualDriftThreshold) {
                driftStatusMap.put(assumptionId, DriftStatus.GRADUAL_DRIFT);

                return new DriftResult(
                        assumptionId,
                        DriftType.GRADUAL,
                        actualValue,
                        threshold,
                        avgDeviation,
                        Instant.now(),
                        String.format("渐进漂移：最近 %d 次验证平均偏离 %.1f%%",
                                history.size(), avgDeviation * 100),
                        generateDriftRecommendation(assumption, DriftType.GRADUAL)
                );
            }
        }

        // 3. 检测假设过期（Assumption Expiry）
        Instant lastValid = lastValidationTime.get(assumptionId);
        if (lastValid != null) {
            Duration timeSinceLastValidation = Duration.between(lastValid, Instant.now());
            if (timeSinceLastValidation.compareTo(assumptionExpiryTime) > 0) {
                driftStatusMap.put(assumptionId, DriftStatus.EXPIRED);

                return new DriftResult(
                        assumptionId,
                        DriftType.EXPIRED,
                        actualValue,
                        threshold,
                        0.0,
                        Instant.now(),
                        String.format("假设过期：超过 %d 天未验证",
                                assumptionExpiryTime.toDays()),
                        generateDriftRecommendation(assumption, DriftType.EXPIRED)
                );
            }
        }

        // 无漂移
        driftStatusMap.put(assumptionId, DriftStatus.NORMAL);
        return null;
    }

    /**
     * 计算平均偏差。
     */
    private double calculateAverageDeviation(Deque<Double> history, double threshold) {
        return history.stream()
                .mapToDouble(value -> Math.abs(value - threshold) / threshold)
                .average()
                .orElse(0.0);
    }

    /**
     * 生成漂移建议。
     */
    private String generateDriftRecommendation(HarnessAssumptionRegistry.Assumption assumption,
                                                DriftType driftType) {
        return switch (driftType) {
            case SUDDEN -> String.format(
                    "建议立即检查 %s 的假设 '%s'，可能存在系统故障或配置错误。",
                    assumption.advisorName(), assumption.description()
            );
            case GRADUAL -> String.format(
                    "建议调整 %s 的假设阈值，或优化相关组件以恢复假设有效性。",
                    assumption.advisorName()
            );
            case EXPIRED -> String.format(
                    "建议重新验证假设 '%s'，并更新假设注册表。",
                    assumption.description()
            );
        };
    }

    // ==================== 测试扩展点 ====================

    /**
     * 【测试扩展点】直接调用漂移检测算法。
     *
     * <p>包可见方法，供单元测试直接验证漂移检测逻辑，
     * 无需通过 adviseCall 链路触发。
     *
     * <p><b>使用场景</b>：
     * <ul>
     *   <li>单元测试验证特定漂移场景（突然漂移、渐进漂移、过期）</li>
     *   <li>调试和诊断漂移检测算法</li>
     * </ul>
     *
     * @param assumption 待检测的假设
     * @param actualValue 当前实际值
     * @return 漂移检测结果，无漂移时返回 null
     */
    DriftResult detectDriftForTest(HarnessAssumptionRegistry.Assumption assumption, double actualValue) {
        return detectDrift(assumption, actualValue);
    }

    /**
     * 【测试扩展点】注入历史数据。
     *
     * <p>包可见方法，供单元测试直接设置假设的历史验证值，
     * 用于验证渐进漂移检测逻辑。
     *
     * @param assumptionId 假设 ID
     * @param values 历史验证值列表
     */
    void injectHistoryForTest(String assumptionId, Deque<Double> values) {
        assumptionValueHistory.put(assumptionId, new ArrayDeque<>(values));
    }

    /**
     * 【测试扩展点】设置最小样本数。
     *
     * <p>包可见方法，供单元测试调整渐进漂移检测的最小样本数，
     * 便于使用少量数据验证逻辑。
     *
     * @param minSamples 最小样本数
     */
    void setMinSamplesForTest(int minSamples) {
        this.gradualDriftMinSamples = minSamples;
    }

    // ==================== 漂移处理方法 ====================

    /**
     * 处理漂移告警。
     */
    private void handleDrift(HarnessAssumptionRegistry.Assumption assumption,
                             DriftResult drift, String taskId) {
        log.warn("[O层·漂移检测] 检测到假设漂移: {}", drift.message());

        // 调用所有漂移告警处理器
        for (DriftAlertHandler handler : driftAlertHandlers) {
            try {
                handler.onDriftDetected(drift, assumption, taskId);
            } catch (Exception e) {
                log.error("[O层·漂移检测] 漂移告警处理器执行失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 注册漂移告警处理器。
     */
    public void registerDriftAlertHandler(DriftAlertHandler handler) {
        driftAlertHandlers.add(handler);
        log.info("[O层·漂移检测] 注册漂移告警处理器: {}", handler.getClass().getSimpleName());
    }

    // ==================== 报告生成方法 ====================

    /**
     * 生成漂移检测报告。
     *
     * <p>对应书中 Ch8 §8.5.3 漂移报告与告警。
     */
    public DriftReport generateDriftReport() {
        List<DriftResult> driftedAssumptions = new ArrayList<>();
        List<HarnessAssumptionRegistry.Assumption> normalAssumptions = new ArrayList<>();
        List<HarnessAssumptionRegistry.Assumption> expiredAssumptions = new ArrayList<>();

        for (HarnessAssumptionRegistry.Assumption assumption : assumptionRegistry.getAllActiveAssumptions()) {
            DriftStatus status = driftStatusMap.getOrDefault(assumption.id(), DriftStatus.NORMAL);

            switch (status) {
                case SUDDEN_DRIFT, GRADUAL_DRIFT -> {
                    // 从最近验证中提取漂移结果
                    Deque<Double> history = assumptionValueHistory.get(assumption.id());
                    if (history != null && !history.isEmpty()) {
                        double lastValue = history.getLast();
                        double deviation = Math.abs(lastValue - assumption.threshold()) / assumption.threshold();
                        driftedAssumptions.add(new DriftResult(
                                assumption.id(),
                                status == DriftStatus.SUDDEN_DRIFT ? DriftType.SUDDEN : DriftType.GRADUAL,
                                lastValue,
                                assumption.threshold(),
                                deviation,
                                Instant.now(),
                                "",
                                ""
                        ));
                    }
                }
                case EXPIRED -> expiredAssumptions.add(assumption);
                case NORMAL -> normalAssumptions.add(assumption);
            }
        }

        return new DriftReport(
                Instant.now(),
                assumptionRegistry.getTotalAssumptionCount(),
                driftedAssumptions.size(),
                expiredAssumptions.size(),
                driftedAssumptions,
                normalAssumptions,
                expiredAssumptions
        );
    }

    // ==================== 配置方法 ====================

    public void setSuddenDriftThreshold(double threshold) {
        this.suddenDriftThreshold = threshold;
        log.info("[O层·漂移检测] 突然漂移阈值设置为: {}%", threshold * 100);
    }

    public void setGradualDriftThreshold(double threshold) {
        this.gradualDriftThreshold = threshold;
        log.info("[O层·漂移检测] 渐进漂移阈值设置为: {}%", threshold * 100);
    }

    public void setAssumptionExpiryTime(Duration duration) {
        this.assumptionExpiryTime = duration;
        log.info("[O层·漂移检测] 假设过期时间设置为: {} 天", duration.toDays());
    }

    // ==================== 辅助方法 ====================

    private String extractTaskId(Map<String, Object> context) {
        return context.getOrDefault("task.id", "unknown").toString();
    }

    // ==================== 内部类型 ====================

    /** 漂移类型 */
    public enum DriftType {
        SUDDEN,  // 突然漂移
        GRADUAL, // 渐进漂移
        EXPIRED  // 假设过期
    }

    /** 漂移状态 */
    public enum DriftStatus {
        NORMAL,        // 正常
        SUDDEN_DRIFT,  // 突然漂移
        GRADUAL_DRIFT, // 渐进漂移
        EXPIRED        // 已过期
    }

    /** 漂移检测结果 */
    public record DriftResult(
            String assumptionId,
            DriftType driftType,
            double actualValue,
            double expectedThreshold,
            double deviation,
            Instant detectedAt,
            String message,
            String recommendation
    ) {
        public boolean hasDrift() {
            return driftType != null;
        }

        @Override
        public String toString() {
            return String.format("DriftResult[assumption=%s, type=%s, deviation=%.1f%%]",
                    assumptionId, driftType, deviation * 100);
        }
    }

    /** 漂移检测报告 */
    public record DriftReport(
            Instant generatedAt,
            int totalAssumptions,
            int driftedCount,
            int expiredCount,
            List<DriftResult> driftedAssumptions,
            List<HarnessAssumptionRegistry.Assumption> normalAssumptions,
            List<HarnessAssumptionRegistry.Assumption> expiredAssumptions
    ) {
        @Override
        public String toString() {
            return String.format("DriftReport[total=%d, drifted=%d, expired=%d]",
                    totalAssumptions, driftedCount, expiredCount);
        }
    }

    /** 漂移告警处理器接口 */
    @FunctionalInterface
    public interface DriftAlertHandler {
        void onDriftDetected(DriftResult drift, HarnessAssumptionRegistry.Assumption assumption, String taskId);
    }
}
