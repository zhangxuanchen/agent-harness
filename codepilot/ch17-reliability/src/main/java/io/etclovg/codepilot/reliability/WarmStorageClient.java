package io.etclovg.codepilot.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 温存储客户端。
 * <p>对应书中 Ch17 §17.6 —— 分层存储中的温存储访问。
 * <p>用于存放中频访问的历史指标与近期 trace，介于
 * {@link HotStorageClient} 与 {@link ColdStorageClient} 之间。
 */
@Component
public class WarmStorageClient {

    private static final Logger log = LoggerFactory.getLogger(WarmStorageClient.class);

    /**
     * 写入温存储。
     *
     * @param key  数据键
     * @param data 数据内容
     */
    public void put(String key, String data) {
        log.debug("写入温存储: key={}", key);
    }

    /**
     * 从温存储读取。
     *
     * @param key 数据键
     * @return 数据内容
     */
    public Optional<String> get(String key) {
        log.debug("读取温存储: key={}", key);
        return Optional.empty();
    }
}
