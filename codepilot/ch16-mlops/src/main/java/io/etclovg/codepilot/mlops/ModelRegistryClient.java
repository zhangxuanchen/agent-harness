package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 模型注册表客户端。
 * <p>对应书中 Ch16 §16.2 —— 与模型注册表交互的客户端。
 * <p>提供模型版本的注册、查询与状态更新能力，供 MLOps 流水线
 * 在训练完成后将版本信息写入注册表。
 */
@Component
public class ModelRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryClient.class);

    /**
     * 注册模型版本。
     *
     * @param modelId  模型 ID
     * @param version  版本号
     * @param accuracy 准确率
     * @return 注册后的版本 ID
     */
    public String register(String modelId, String version, double accuracy) {
        log.info("注册模型版本: model={}, version={}, accuracy={}", modelId, version, accuracy);
        return modelId + ":" + version;
    }

    /**
     * 查询模型最新版本。
     *
     * @param modelId 模型 ID
     * @return 版本信息
     */
    public Optional<RegistryEntry> getLatest(String modelId) {
        log.debug("查询最新版本: model={}", modelId);
        return Optional.empty();
    }

    /**
     * 拉取自上次检查以来发布的新版本列表。
     * <p>对应书中 §16.5 {@code registryClient.getNewVersionsSince(lastCheck)}——
     * 由 {@link ModelUpdateManager} 定时轮询，触发回归评估 + 影子流量验证。
     *
     * @param since 上次检查时间点（首次为 {@code null} 表示拉取全部）
     * @return 新版本列表（按发布时间升序），无更新返回空列表
     */
    public List<ModelVersion> getNewVersionsSince(Instant since) {
        log.debug("轮询新版本: since={}", since);
        return List.of();
    }

    /**
     * 注册表条目。
     *
     * @param modelId   模型 ID
     * @param version   版本号
     * @param accuracy  准确率
     * @param registeredAt 注册时间
     */
    public record RegistryEntry(String modelId, String version, double accuracy, Instant registeredAt) {
    }
}
