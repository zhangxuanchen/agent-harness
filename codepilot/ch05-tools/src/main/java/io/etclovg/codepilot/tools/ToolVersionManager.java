package io.etclovg.codepilot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具版本管理器。
 * <p>对应书中 Ch05 §5.6 —— 工具版本治理与灰度。
 * <p>维护每个工具的已发布版本、当前激活版本与灰度比例，
 * 供调用方在运行时选择具体版本，支持灰度发布与回滚。
 */
@Component
public class ToolVersionManager {

    private static final Logger log = LoggerFactory.getLogger(ToolVersionManager.class);

    private final Map<String, VersionInfo> activeVersions = new ConcurrentHashMap<>();

    /**
     * 注册工具版本。
     *
     * @param toolName      工具名称
     * @param version       版本号
     * @param canaryPercent 灰度比例（0-100）
     */
    public void register(String toolName, String version, int canaryPercent) {
        activeVersions.put(toolName, new VersionInfo(version, canaryPercent));
        log.info("注册工具版本: tool={}, version={}, canary={}%", toolName, version, canaryPercent);
    }

    /**
     * 获取工具当前激活版本。
     *
     * @param toolName 工具名称
     * @return 版本信息，未注册返回 null
     */
    public VersionInfo getActiveVersion(String toolName) {
        return activeVersions.get(toolName);
    }

    /**
     * 将工具完全切换到指定版本。
     *
     * @param toolName 工具名称
     * @param version  版本号
     */
    public void promote(String toolName, String version) {
        activeVersions.put(toolName, new VersionInfo(version, 100));
        log.info("提升版本至全量: tool={}, version={}", toolName, version);
    }

    /**
     * 回滚到指定版本。
     *
     * @param toolName 工具名称
     * @param version  版本号
     */
    public void rollback(String toolName, String version) {
        activeVersions.put(toolName, new VersionInfo(version, 100));
        log.warn("回滚工具版本: tool={}, version={}", toolName, version);
    }

    /**
     * 工具版本信息。
     *
     * @param version       版本号
     * @param canaryPercent 灰度比例
     */
    public record VersionInfo(String version, int canaryPercent) {
    }
}
