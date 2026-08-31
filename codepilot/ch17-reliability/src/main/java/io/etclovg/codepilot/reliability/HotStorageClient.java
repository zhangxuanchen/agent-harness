package io.etclovg.codepilot.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 热存储客户端。
 * <p>对应书中 Ch17 §17.6 —— 分层存储中的热存储访问。
 * <p>用于存放近期高频访问的 trace 与实时指标，访问延迟最低但成本最高，
 * 与 {@link WarmStorageClient}、{@link ColdStorageClient} 构成三层存储。
 */
@Component
public class HotStorageClient {

    private static final Logger log = LoggerFactory.getLogger(HotStorageClient.class);

    /**
     * 写入热存储。
     *
     * @param key  数据键
     * @param data 数据内容
     */
    public void put(String key, String data) {
        log.debug("写入热存储: key={}", key);
    }

    /**
     * 从热存储读取。
     *
     * @param key 数据键
     * @return 数据内容
     */
    public Optional<String> get(String key) {
        log.debug("读取热存储: key={}", key);
        return Optional.empty();
    }
}
