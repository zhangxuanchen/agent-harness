package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 金丝雀控制器。对应书中 Ch16 §16.3。
 * <p>渐进式 Canary 部署控制器，管理四阶段（5%/20%/50%/100%）的自动推进和回滚逻辑。
 *
 * <p>每阶段先以灾难阈值快速判定（错误率超阈值立即回滚），再用
 * {@link ProportionZTest} 双比例 z 检验判定统计显著性。任一阶段检测到
 * 统计显著的退化，立即将 Canary 版本下线，全部流量切回稳定版本，
 * 并触发事故复盘流程（见 KP 16.4.1）。
 *
 * <p>部署前由 {@link SampleSizeCalculator} 预估各阶段所需样本量，校准观察时长，
 * 避免"小流量样本量不够、大流量风险敞口太大"的取舍失衡。
 */
@Component
public class CanaryController {

    private static final Logger log = LoggerFactory.getLogger(CanaryController.class);

    private final TrafficRouter router;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public CanaryController(TrafficRouter router) {
        this.router = router;
    }

    /**
     * 启动金丝雀发布：依次执行 5%→20%→50%→100% 四阶段。
     * <p>对应书中 §16.3 {@code startCanary}。
     */
    public void startCanary(CanaryConfig config) {
        // 部署前预估各阶段所需样本量，校准观察时长（避免样本不足导致检验失效）
        int needed = SampleSizeCalculator.samplesPerGroup(
                0.85, 0.80, ProportionZTest.Z_ALPHA_05, 0.842); // ≈714/组
        log.info("Canary 启动：检测 5pp 退化需 {} 样本/组", needed);

        List<CanaryStage> stages = List.of(
                new CanaryStage(0.05, Duration.ofHours(1),
                        metrics -> evaluateStage(metrics, 0.30)),   // 灾难阈值 30pp
                new CanaryStage(0.20, Duration.ofHours(4),
                        metrics -> evaluateStage(metrics, 0.05)),   // 功能级 5pp
                new CanaryStage(0.50, Duration.ofHours(24),
                        metrics -> evaluateStage(metrics, 0.03))    // 细微 3pp
        );
        executeStagesSequentially(stages, config, 0);
    }

    /**
     * 单阶段退化判定：先过灾难阈值快速回滚，再用双比例 z 检验判定统计显著性。
     * <p>样本不足（n·p̄ &lt; 5）时 z 检验正态近似失效，此处依赖错误率阈值兜底。
     */
    private DegradationResult evaluateStage(CanaryMetrics metrics, double disasterThreshold) {
        // 灾难性退化直接回滚（无需等统计显著）
        if (metrics.errorRate() > disasterThreshold) {
            return DegradationResult.of("rollback", List.of("canary"),
                    "错误率 " + metrics.errorRate() + " 超灾难阈值 " + disasterThreshold);
        }
        // 双比例 z 检验：z < -1.645 → 退化统计显著
        boolean significant = ProportionZTest.isSignificantDegradation(
                metrics.successBaseline(), metrics.totalBaseline(),
                metrics.successCandidate(), metrics.totalCandidate(),
                ProportionZTest.Z_ALPHA_05);
        return significant
                ? DegradationResult.of("rollback", List.of("canary"), "z 检验显著退化")
                : DegradationResult.none();
    }

    /**
     * 顺序执行各阶段：每阶段设置流量 → 等待观察时长 → 采集指标 → 判定 → 推进或回滚。
     */
    private void executeStagesSequentially(List<CanaryStage> stages,
                                           CanaryConfig config, int index) {
        if (index >= stages.size()) {
            // 全部阶段通过 → 全量切到 Canary，并安排 48 小时终验
            router.routeAllToCanary(config.getCanaryId());
            scheduleFinalVerification(config, Duration.ofHours(48));
            return;
        }
        CanaryStage stage = stages.get(index);
        router.setCanaryPercentage(stage.getTrafficPercent());

        scheduler.schedule(() -> {
            CanaryMetrics metrics = collectMetrics(stage.getDuration());
            DegradationResult result = stage.getThreshold().evaluate(metrics);

            if (result.isDegraded()) {
                router.routeAllToStable();
                triggerIncident(result);
                triggerAlarm("Canary 退化检测", result.getDetails());
                log.error("Canary 回滚：阶段 {} 失败，阈值={}",
                        stage.getTrafficPercent(), result.getDetails());
                return;
            }
            log.info("Canary 阶段通过：{}% 流量，观察 {} 小时",
                    stage.getTrafficPercent() * 100, stage.getDuration().toHours());
            executeStagesSequentially(stages, config, index + 1);
        }, stage.getDuration().toSeconds(), TimeUnit.SECONDS);
    }

    // ==================== 协作桩（生产实现见可观测/告警/工单平台对接） ====================

    /** 采集观察期内的双版本指标快照。教学桩返回健康指标。 */
    private CanaryMetrics collectMetrics(Duration window) {
        return CanaryMetrics.healthy("stage-" + window.toHours() + "h", 5);
    }

    /** 从 Canary 退化结果创建事故复盘。 */
    private void triggerIncident(DegradationResult result) {
        log.warn("[Canary] 触发事故复盘: {}", result.getDetails());
    }

    /** 触发告警。 */
    private void triggerAlarm(String title, String details) {
        log.warn("[Canary] 告警: {} - {}", title, details);
    }

    /** 安排全量上线后的终验（48 小时观察，确认无退化再下线旧版本）。 */
    private void scheduleFinalVerification(CanaryConfig config, Duration delay) {
        scheduler.schedule(() ->
                        log.info("[Canary] 终验通过：{} 已稳定运行 {} 小时，下线旧版本",
                                config.getCanaryId(), delay.toHours()),
                delay.toSeconds(), TimeUnit.SECONDS);
    }
}
