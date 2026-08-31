package io.etclovg.codepilot.data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 模型训练器。
 * <p>对应书中 Ch13 §13.3 —— 数据飞轮的模型微调阶段。
 * <p>屏蔽具体训练框架（本地微调、托管训练服务）的差异，供数据飞轮
 * 在评估通过后触发增量微调（仅本轮新样本，非全量重训）。
 * 本接口方法均提供默认桩实现，便于直接构造实例用于测试与桩场景。
 */
public interface ModelTrainer {

    /**
     * 增量微调模型。
     *
     * @param pairs 本轮训练对
     * @return 新模型版本（桩实现返回带时间戳的版本）
     */
    default ModelVersion incrementalFineTune(List<TrainingPair> pairs) {
        return new ModelVersion(
                "model-" + System.currentTimeMillis(),
                "v" + System.currentTimeMillis(),
                "base", "ds-flywheel", 0.0,
                ModelVersion.Status.DRAFT, Instant.now()
        );
    }

    /**
     * 提交训练任务（保留原有方法，默认桩实现）。
     *
     * @param datasetId   训练数据集 ID
     * @param baseModel   基座模型名称
     * @param hyperParams 超参数
     * @return 训练任务 ID
     */
    default String submitTraining(String datasetId, String baseModel, Map<String, Object> hyperParams) {
        return "training-" + System.currentTimeMillis();
    }

    /**
     * 查询训练状态（保留原有方法，默认桩实现）。
     *
     * @param trainingId 训练任务 ID
     * @return 状态字符串
     */
    default String getStatus(String trainingId) {
        return "DONE";
    }

    /**
     * 取消训练任务（保留原有方法，默认桩实现）。
     *
     * @param trainingId 训练任务 ID
     * @return 取消成功返回 true
     */
    default boolean cancel(String trainingId) {
        return true;
    }
}
