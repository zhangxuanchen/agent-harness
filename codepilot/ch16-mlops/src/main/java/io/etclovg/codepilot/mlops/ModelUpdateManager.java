package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 模型更新管理器。
 * <p>对应书中 Ch16 §16.5.1 —— 模型提供方静默更新的平滑过渡。
 *
 * <p>模型提供方不定期更新版本（如 GPT-5.3 → GPT-5.4），API 接口不变但输出质量可能
 * 静默退化。本组件按"监听 → 回归评估 → 影子流量 A/B → 退化自动回退 → Canary 部署"
 * 五步流水线对新版本做门禁式验证，退化则废弃新版本、保持当前版本，避免静默退化流入生产。
 *
 * <p>这是模型静默更新场景的核心防线——传统基于接口契约的测试在"接口不变但实现改变"
 * 的封装模式下完全失效，必须用语义评估（回归套件 + llm-as-judge）+ 统计显著检验
 * （影子流量双比例 z 检验）替代。
 */
@Component
public class ModelUpdateManager {

    private static final Logger logger = LoggerFactory.getLogger(ModelUpdateManager.class);

    /** 回归评估退化阈值（成功率降低 3 个百分点即拒绝）。 */
    private static final double REGRESSION_DEGRADATION_THRESHOLD = 0.03;
    /** 影子流量观察时长。 */
    private static final Duration SHADOW_OBSERVE = Duration.ofHours(24);
    /** 轮询间隔：每 5 分钟检查一次模型注册表。 */
    private static final long POLL_INTERVAL_MS = 300_000L;

    private final ModelRegistryClient registryClient;
    private final EvalWorkerPool evalPool;
    private final ShadowTrafficRouter shadowRouter;
    private final ShadowComparator comparator;
    private final CanaryController canaryController;
    private final AlarmService alarmService;

    /** 上次检查模型注册表的时间点。 */
    private Instant lastCheck = Instant.now();
    /** 当前生产稳定版本（教学桩默认占位；生产中由发布流程写入）。 */
    private ModelVersion currentVersion = new ModelVersion(
            "stable-current", "stable", "current", "Current Stable",
            128_000, 0.0, 0.0, Instant.EPOCH, null,
            List.of(), java.util.Map.of());
    /** 影子流量终验调度器（24 小时后做 A/B 对比）。 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public ModelUpdateManager(ModelRegistryClient registryClient,
                              EvalWorkerPool evalPool,
                              ShadowTrafficRouter shadowRouter,
                              ShadowComparator comparator,
                              CanaryController canaryController,
                              AlarmService alarmService) {
        this.registryClient = registryClient;
        this.evalPool = evalPool;
        this.shadowRouter = shadowRouter;
        this.comparator = comparator;
        this.canaryController = canaryController;
        this.alarmService = alarmService;
    }

    /**
     * 更新状态。
     */
    public enum UpdateStatus {
        PENDING, IN_PROGRESS, COMPLETED, FAILED, ROLLED_BACK
    }

    /**
     * 定时检查模型更新，触发回归评估 + 影子流量 A/B 验证。
     * <p>对应书中 §16.5 {@code checkModelUpdates()}——每 5 分钟轮询模型注册表，
     * 检测到新版本后依次执行回归评估与 24 小时影子流量对比，退化则回退，通过则进入 Canary 流程。
     */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void checkModelUpdates() {
        List<ModelVersion> updates = registryClient.getNewVersionsSince(lastCheck);

        for (ModelVersion newVersion : updates) {
            logger.info("检测到模型更新: {}", newVersion.getId());

            // Step 1: 全量回归评估
            EvalResult evalResult = evalPool.runParallel(
                    newVersion.getConfig(),
                    EvalRegistry.loadRegressionSuite(100),
                    30, TimeUnit.MINUTES
            );

            if (evalResult.degradation() > REGRESSION_DEGRADATION_THRESHOLD) {
                logger.warn("回归评估发现退化 {}%, 模型 {} 被拒绝",
                        String.format("%.1f", evalResult.degradation() * 100), newVersion.getId());
                alarmService.trigger("模型更新退化", newVersion.getId(), evalResult);
                return; // 退化超过 3%，拒绝更新
            }

            // Step 2: 影子流量 A/B 验证（24 小时）
            shadowRouter.startShadow(newVersion.getId(), SHADOW_OBSERVE);
            scheduler.schedule(() -> {
                ShadowComparisonResult shadowResult = comparator.compare(
                        currentVersion.getId(), newVersion.getId(),
                        shadowRouter.getShadowDuration()
                );

                if (shadowResult.isSignificantlyDegraded(REGRESSION_DEGRADATION_THRESHOLD)) {
                    // 退化：废弃新版本
                    shadowRouter.stopShadow(newVersion.getId());
                    alarmService.trigger("影子流量验证退化", newVersion.getId(), shadowResult);
                } else {
                    // 通过：进入 Canary 流程
                    shadowRouter.stopShadow(newVersion.getId());
                    canaryController.startCanary(new CanaryConfig(newVersion));
                }
            }, SHADOW_OBSERVE.toHours(), TimeUnit.HOURS);

            lastCheck = Instant.now();
        }
    }

    /**
     * 执行模型更新（手动入口，保留旧 API 兼容）。
     */
    public UpdateStatus updateModel(String agentId, String newModelId) {
        logger.info("[ModelUpdate] 更新模型: agent={}, newModel={}", agentId, newModelId);
        return UpdateStatus.COMPLETED;
    }

    /**
     * 回滚模型更新（手动入口，保留旧 API 兼容）。
     */
    public UpdateStatus rollback(String agentId) {
        logger.warn("[ModelUpdate] 回滚: agent={}", agentId);
        return UpdateStatus.ROLLED_BACK;
    }
}
