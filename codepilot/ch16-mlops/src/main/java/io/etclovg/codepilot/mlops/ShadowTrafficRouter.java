package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 影子流量路由器。
 * <p>对应书中 Ch16 §16.5 —— 影子流量的分流与路由。
 * <p>将生产请求复制一份路由到候选版本，结果不影响用户响应，
 * 仅供 {@link ShadowComparator} 离线对比。
 */
@Component
public class ShadowTrafficRouter {

    private static final Logger log = LoggerFactory.getLogger(ShadowTrafficRouter.class);

    private volatile int shadowPercent = 0;

    /** 每个候选版本的影子会话：记录启动时间与计划观察时长，供 {@link #getShadowDuration} 计算。 */
    private final Map<String, ShadowSession> sessions = new ConcurrentHashMap<>();

    /** 影子会话状态。 */
    private record ShadowSession(Instant startedAt, Duration plannedDuration) {
        Duration elapsed() {
            return Duration.between(startedAt, Instant.now());
        }
    }

    /**
     * 设置影子流量比例。
     *
     * @param percent 比例（0-100）
     */
    public void setShadowPercent(int percent) {
        this.shadowPercent = Math.max(0, Math.min(100, percent));
        log.info("设置影子流量比例: {}%", shadowPercent);
    }

    /**
     * 判断请求是否进入影子通道。
     *
     * @param requestId 请求 ID
     * @return 进入返回 true
     */
    public boolean shouldShadow(String requestId) {
        if (shadowPercent <= 0) {
            return false;
        }
        boolean shadow = (requestId == null ? 0 : Math.abs(requestId.hashCode())) % 100 < shadowPercent;
        return shadow;
    }

    public int getShadowPercent() {
        return shadowPercent;
    }

    /**
     * 启动指定候选版本的影子流量会话。
     * <p>对应书中 §16.5 {@code shadowRouter.startShadow(newVersion.getId(), Duration.ofHours(24))}——
     * 将候选版本接入影子通道，按计划时长观察，结果不返回用户。
     *
     * @param versionId       候选版本 ID
     * @param plannedDuration 计划观察时长（如 24 小时）
     */
    public void startShadow(String versionId, Duration plannedDuration) {
        Duration d = plannedDuration == null ? Duration.ZERO : plannedDuration;
        sessions.put(versionId, new ShadowSession(Instant.now(), d));
        // 影子模式默认 100% 生产流量复制（结果不返回用户）
        this.shadowPercent = 100;
        log.info("启动影子流量: version={}, plannedDuration={}h", versionId, d.toHours());
    }

    /**
     * 停止指定候选版本的影子流量会话。
     * <p>对应书中 §16.5 {@code shadowRouter.stopShadow(newVersion.getId())}。
     *
     * @param versionId 候选版本 ID
     */
    public void stopShadow(String versionId) {
        ShadowSession removed = sessions.remove(versionId);
        if (removed != null) {
            log.info("停止影子流量: version={}, elapsed={}h", versionId, removed.elapsed().toHours());
        }
        if (sessions.isEmpty()) {
            this.shadowPercent = 0;
        }
    }

    /**
     * 返回当前影子会话的已观察时长。
     * <p>对应书中 §16.5 {@code shadowRouter.getShadowDuration()}——
     * 供 {@link ShadowComparator#compare} 截取对比窗口。多会话时返回最早启动会话的已观察时长。
     *
     * @return 已观察时长；无活跃会话返回 {@link Duration#ZERO}
     */
    public Duration getShadowDuration() {
        return sessions.values().stream()
                .map(ShadowSession::elapsed)
                .min(Duration::compareTo)
                .orElse(Duration.ZERO);
    }
}
