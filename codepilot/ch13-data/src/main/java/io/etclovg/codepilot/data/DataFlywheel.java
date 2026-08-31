package io.etclovg.codepilot.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 数据飞轮。
 * <p>对应书中 Ch13 §13.3 —— 数据飞轮：从运行中持续学习。
 * <p>五阶段飞轮编排器，48 小时周期执行
 * Collect→Clean→Train→Deploy→Evaluate，将结构化反馈闭环到训练数据中。
 * 偏差防护机制（{@link BiasGuard}）保留 20% 边缘案例防止过度拟合高频场景。
 */
@Component
public class DataFlywheel {

    private static final Logger log = LoggerFactory.getLogger(DataFlywheel.class);

    /** O 层：错误案例采集 */
    private final ErrorCollector collector;
    /** 数据清洗：去重+归一化+标注 */
    private final DataCleaner cleaner;
    /** 模型微调：增量训练 */
    private final ModelTrainer trainer;
    /** 部署上线：Canary 灰度 */
    private final DeploymentManager deployer;
    /** O 层评估：准确率/延迟对比 */
    private final MetricsEvaluator evaluator;

    private final List<FlywheelCycle> cycles = new ArrayList<>();

    /**
     * 构造函数注入五阶段依赖。
     *
     * @param collector 错误采集器
     * @param cleaner   数据清洗器
     * @param trainer   模型训练器
     * @param deployer  部署管理器
     * @param evaluator 指标评估器
     */
    public DataFlywheel(ErrorCollector collector,
                        DataCleaner cleaner,
                        ModelTrainer trainer,
                        DeploymentManager deployer,
                        MetricsEvaluator evaluator) {
        this.collector = collector;
        this.cleaner = cleaner;
        this.trainer = trainer;
        this.deployer = deployer;
        this.evaluator = evaluator;
    }

    /**
     * 飞轮迭代：每天凌晨 2 点执行。
     * <p>1. Collect 采集过去 48 小时错误案例；2. Clean 清洗并保留边缘案例；
     * 3. Train 增量微调；4. Deploy Canary 灰度；5. Evaluate 24 小时后对比。
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void iterate() {
        // 1. Collect: 采集过去 48 小时的错误案例
        List<ErrorSample> samples = collector.collect(Duration.ofHours(48));
        if (samples.size() < 50) {
            log.debug("[DataFlywheel] 样本不足 {} 条，跳过本轮", samples.size());
            return;  // 样本不足则跳过本轮
        }

        // 2. Clean: 去重+归一化+自动标注，保留 20% 边缘案例
        List<TrainingPair> pairs = cleaner.clean(samples);
        BiasGuard retention = BiasGuard.reserveEdgeCases(pairs, 0.2);

        // 3. Train: 增量微调（仅本轮新样本，非全量重训）
        ModelVersion newVersion = trainer.incrementalFineTune(pairs);

        // 4. Deploy: Canary 10% 流量灰度
        deployer.canaryDeploy(newVersion, 0.1);

        // 5. Evaluate: 24 小时后对比准确率与故障检测
        evaluator.scheduleComparison(newVersion, Duration.ofHours(24),
            result -> {
                if (result.accuracyDelta() > 0) {
                    deployer.rollout(newVersion, 1.0);          // 提升→全量
                } else if (result.edgeDegradation() > 0.02) {
                    deployer.rollback(newVersion);              // 边缘故障>2pp→回滚
                    alert("飞轮边缘案例故障，触发人工审核");
                }
            });
    }

    /**
     * 告警（桩实现，仅记日志）。
     *
     * @param message 告警信息
     */
    private void alert(String message) {
        log.warn("[DataFlywheel] {}", message);
    }

    /**
     * 飞轮周期（保留原有内部记录）。
     */
    public record FlywheelCycle(
            int cycleNumber,
            String dataInput,
            String modelOutput,
            double qualityScore,
            long timestamp
    ) {
    }

    /**
     * 执行一个飞轮周期（保留原有便捷方法）。
     */
    public FlywheelCycle executeCycle(String data) {
        int cycleNum = cycles.size() + 1;
        FlywheelCycle cycle = new FlywheelCycle(
                cycleNum, data, "", 0.8, System.currentTimeMillis()
        );
        cycles.add(cycle);
        log.info("[DataFlywheel] 周期 {} 完成", cycleNum);
        return cycle;
    }

    /** @return 飞轮周期数 */
    public int getCycleCount() {
        return cycles.size();
    }

    /** @return 所有周期 */
    public List<FlywheelCycle> getCycles() {
        return Collections.unmodifiableList(cycles);
    }
}
