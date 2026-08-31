package io.etclovg.codepilot.data;

import java.time.Instant;

/**
 * 模型版本信息记录。
 * <p>对应书中 Ch13 §13.3 —— 数据飞轮产出的模型版本元数据。
 * <p>描述单个模型版本的标识、来源、训练指标与状态，
 * 供 {@link ModelTrainer#incrementalFineTune} 产出与 {@link DeploymentManager} 部署引用。
 *
 * @param modelId     模型 ID
 * @param version     版本号
 * @param baseModel   基座模型
 * @param datasetId   训练数据集 ID
 * @param accuracy    准确率
 * @param status      版本状态
 * @param createdAt   创建时间
 */
public record ModelVersion(
        String modelId,
        String version,
        String baseModel,
        String datasetId,
        double accuracy,
        Status status,
        Instant createdAt
) {

    /**
     * 构造带默认字段的版本信息。
     *
     * @param modelId 模型 ID
     * @param version 版本号
     * @return 版本信息
     */
    public static ModelVersion of(String modelId, String version) {
        return new ModelVersion(modelId, version, "base", "ds-default", 0.0, Status.DRAFT, Instant.now());
    }

    /**
     * 是否可用于部署。
     *
     * @return 已批准或已发布返回 true
     */
    public boolean isDeployable() {
        return status == Status.APPROVED || status == Status.PUBLISHED;
    }

    /**
     * 模型版本状态。
     */
    public enum Status {
        /** 草稿 */
        DRAFT,
        /** 训练中 */
        TRAINING,
        /** 评估中 */
        EVALUATING,
        /** 已批准 */
        APPROVED,
        /** 已发布 */
        PUBLISHED,
        /** 已废弃 */
        DEPRECATED
    }
}
