package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 流量路由器。
 * <p>对应书中 Ch16 §16.5 —— 多版本间的流量路由。
 * <p>在金丝雀、A/B 测试与影子流量场景中按比例将流量分发到不同版本，
 * 是 {@link ShadowTrafficRouter} 与 {@code CanaryController} 的基础组件。
 */
@Component
public class TrafficRouter {

    private static final Logger log = LoggerFactory.getLogger(TrafficRouter.class);

    /** 当前 Canary 流量占比（0-1），0 表示全部稳定版本。 */
    private double canaryPercentage = 0.0;
    /** 当前 Canary 版本标识。 */
    private String canaryVersion;

    /**
     * 设置 Canary 流量占比（0-1）。
     * <p>对应书中 §16.3 {@code router.setCanaryPercentage(stage.getTrafficPercent())}。
     */
    public void setCanaryPercentage(double percent) {
        this.canaryPercentage = Math.max(0.0, Math.min(1.0, percent));
        log.info("[TrafficRouter] 设置 Canary 流量: {}%", this.canaryPercentage * 100);
    }

    /** 全部流量切到 Canary 版本（100%）。对应书中 {@code router.routeAllToCanary(canaryId)}。 */
    public void routeAllToCanary(String canaryId) {
        this.canaryVersion = canaryId;
        this.canaryPercentage = 1.0;
        log.info("[TrafficRouter] 全量切到 Canary: {}", canaryId);
    }

    /** 全部流量切回稳定版本（0% Canary）。对应书中 {@code router.routeAllToStable()}。 */
    public void routeAllToStable() {
        this.canaryPercentage = 0.0;
        log.warn("[TrafficRouter] 全量切回稳定版本（Canary 回滚）");
    }

    /** 当前 Canary 流量占比（0-1）。 */
    public double getCanaryPercentage() {
        return canaryPercentage;
    }

    /**
     * 选择流量目标版本。
     *
     * @param versions 候选版本及其权重
     * @return 选中的版本
     */
    public String route(java.util.Map<String, Integer> versions) {
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        int total = versions.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            return versions.keySet().iterator().next();
        }
        int pick = java.util.concurrent.ThreadLocalRandom.current().nextInt(total);
        int acc = 0;
        for (java.util.Map.Entry<String, Integer> e : versions.entrySet()) {
            acc += e.getValue();
            if (pick < acc) {
                log.debug("路由流量: selected={}, pick={}", e.getKey(), pick);
                return e.getKey();
            }
        }
        return versions.keySet().iterator().next();
    }
}
