package io.etclovg.codepilot.future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置束管理器。
 * <p>对应书中 Ch19 §19.3 —— Agent 配置束的统一管理。
 * <p>将 Agent 的提示词、工具集、模型配置等打包为可版本化的配置束，
 * 支持灰度发布、回滚与多环境切换。
 */
@Component
public class ConfigurationBundleManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationBundleManager.class);

    private final Map<String, String> bundles = new ConcurrentHashMap<>();

    /**
     * 发布配置束。
     *
     * @param bundleId 配置束 ID
     * @param content  配置内容
     */
    public void publish(String bundleId, String content) {
        bundles.put(bundleId, content);
        log.info("发布配置束: id={}, size={}", bundleId, content == null ? 0 : content.length());
    }

    /**
     * 获取配置束。
     *
     * @param bundleId 配置束 ID
     * @return 配置内容
     */
    public String get(String bundleId) {
        return bundles.get(bundleId);
    }
}
