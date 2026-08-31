package io.etclovg.codepilot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canary 过滤器：灰度发布时控制工具的流量分配
 * 对应书中 Ch05 §5.3 —— 工具版本灰度发布
 */
@Component
public class CanaryFilter {

    private static final Logger log = LoggerFactory.getLogger(CanaryFilter.class);

    private final Map<String, CanaryConfig> canaryConfigs = new ConcurrentHashMap<>();
    private final Map<String, Integer> trafficAssignments = new ConcurrentHashMap<>();
    private int defaultCanaryPercent = 10;

    public CanaryConfig registerCanary(String toolName, String canaryVersion, int percent) {
        CanaryConfig config = new CanaryConfig(
                toolName, canaryVersion, percent, System.currentTimeMillis()
        );
        canaryConfigs.put(toolName, config);
        log.info("[CanaryFilter] 注册金丝雀: tool={}, canaryVersion={}, percent={}%",
                toolName, canaryVersion, percent);
        return config;
    }

    public boolean shouldRouteToCanary(String toolName, String requestId) {
        CanaryConfig config = canaryConfigs.get(toolName);
        if (config == null || config.percent() <= 0) {
            return false;
        }

        int hash = Math.abs(requestId.hashCode()) % 100;
        boolean route = hash < config.percent();

        if (route) {
            log.debug("[CanaryFilter] 路由到金丝雀版本: tool={}, requestId={}, hash={}, threshold={}",
                    toolName, requestId, hash, config.percent());
        }

        return route;
    }

    public CanaryDecision decide(String toolName, String currentVersion, String requestId) {
        CanaryConfig config = canaryConfigs.get(toolName);
        if (config == null) {
            return new CanaryDecision(currentVersion, false, "无金丝雀配置");
        }

        boolean canary = shouldRouteToCanary(toolName, requestId);
        String version = canary ? config.canaryVersion() : currentVersion;
        return new CanaryDecision(version, canary,
                canary ? "路由到金丝雀版本" : "使用当前版本");
    }

    public void updateCanaryPercent(String toolName, int newPercent) {
        CanaryConfig existing = canaryConfigs.get(toolName);
        if (existing != null) {
            canaryConfigs.put(toolName, new CanaryConfig(
                    toolName, existing.canaryVersion(), newPercent, System.currentTimeMillis()
            ));
            log.info("[CanaryFilter] 更新金丝雀比例: tool={}, newPercent={}%", toolName, newPercent);
        }
    }

    public void promoteCanary(String toolName) {
        CanaryConfig existing = canaryConfigs.get(toolName);
        if (existing != null) {
            log.info("[CanaryFilter] 金丝雀版本已提升为正式版: tool={}, version={}",
                    toolName, existing.canaryVersion());
            canaryConfigs.remove(toolName);
        }
    }

    public Map<String, CanaryConfig> getAllConfigs() {
        return Collections.unmodifiableMap(canaryConfigs);
    }

    public int getDefaultCanaryPercent() {
        return defaultCanaryPercent;
    }

    public void setDefaultCanaryPercent(int defaultCanaryPercent) {
        this.defaultCanaryPercent = defaultCanaryPercent;
    }

    public record CanaryConfig(
            String toolName, String canaryVersion,
            int percent, long registeredAt
    ) {}

    public record CanaryDecision(
            String version, boolean isCanary, String reason
    ) {}
}