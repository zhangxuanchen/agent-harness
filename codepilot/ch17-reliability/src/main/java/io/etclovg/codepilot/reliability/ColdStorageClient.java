package io.etclovg.codepilot.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 冷存储客户端。
 * <p>对应书中 Ch17 §17.6 —— 分层存储中的冷存储访问。
 * <p>用于归档低频访问的历史 trace 与指标，成本低但访问延迟高，
 * 与 {@link HotStorageClient}、{@link WarmStorageClient} 构成三层存储。
 */
@Component
public class ColdStorageClient {

    private static final Logger log = LoggerFactory.getLogger(ColdStorageClient.class);

    /**
     * 归档数据到冷存储。
     *
     * @param key  数据键
     * @param data 数据内容
     */
    public void archive(String key, String data) {
        log.info("归档到冷存储: key={}, size={}", key, data == null ? 0 : data.length());
    }

    /**
     * 从冷存储检索数据。
     *
     * @param key 数据键
     * @return 数据内容
     */
    public Optional<String> retrieve(String key) {
        log.info("从冷存储检索: key={}", key);
        return Optional.empty();
    }
}
