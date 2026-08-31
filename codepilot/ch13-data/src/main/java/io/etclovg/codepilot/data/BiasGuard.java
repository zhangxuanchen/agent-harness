package io.etclovg.codepilot.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 偏见守卫。
 * <p>对应书中 Ch13 §13.3 —— 数据飞轮的偏差防护机制。
 * <p>评估集中保留 20% 边缘案例防止过度拟合高频场景（对抗灾难性遗忘），
 * 每次迭代检查边缘准确率，故障超过 2pp 则触发人工审核。同时在训练数据
 * 与模型输出层面检测性别、种族、地域等偏见，在阈值超标时告警或阻断。
 */
@Component
public class BiasGuard {

    private static final Logger log = LoggerFactory.getLogger(BiasGuard.class);

    private static final double DEFAULT_THRESHOLD = 0.1;

    /**
     * 保留边缘案例作为护栏集。
     * <p>从训练对中按 {@code ratio} 比例保留边缘/低频样本，作为防止
     * 灾难性遗忘的护栏集。20% 的保留比例是对抗过拟合的经验下界。
     *
     * @param pairs 训练对
     * @param ratio 保留比例（如 0.2）
     * @return 偏见守卫实例（桩实现，返回新实例）
     */
    public static BiasGuard reserveEdgeCases(List<TrainingPair> pairs, double ratio) {
        log.debug("[BiasGuard] 保留边缘案例: pairs={}, ratio={}",
                pairs != null ? pairs.size() : 0, ratio);
        // 桩实现：实际应按比例切分护栏集并标记
        return new BiasGuard();
    }

    /**
     * 检测数据集偏见指数（保留原有便捷方法）。
     *
     * @param biasIndex 偏见指数（0-1，0 为完全公平）
     * @return 超过阈值返回 true
     */
    public boolean isBiased(double biasIndex) {
        boolean biased = biasIndex > DEFAULT_THRESHOLD;
        if (biased) {
            log.warn("检测到数据偏见: index={}, threshold={}", biasIndex, DEFAULT_THRESHOLD);
        }
        return biased;
    }

    /**
     * 评估偏见并返回处置建议（保留原有便捷方法）。
     *
     * @param biasIndex 偏见指数
     * @return 处置建议
     */
    public String recommend(double biasIndex) {
        if (biasIndex > 0.3) {
            return "BLOCK: 偏见严重，阻断流程并人工复核";
        }
        if (biasIndex > DEFAULT_THRESHOLD) {
            return "WARN: 偏见超标，建议重采样或加权";
        }
        return "PASS: 偏见在可接受范围";
    }
}
