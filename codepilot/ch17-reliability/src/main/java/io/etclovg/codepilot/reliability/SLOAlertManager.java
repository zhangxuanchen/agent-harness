package io.etclovg.codepilot.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SLO 告警管理器。
 * <p>对应书中 Ch17 §17.4 —— SLO 达成情况的告警管理。
 * <p>在 SLO 即将或已违反时生成告警，按严重度路由到不同通知渠道，
 * 供上层 SLO 计算器（如 {@code ThreeTierSLOCalculator}）在判定违反阈值时调用。
 */
@Component
public class SLOAlertManager {

    private static final Logger log = LoggerFactory.getLogger(SLOAlertManager.class);

    private final List<SLOAlert> alerts = Collections.synchronizedList(new ArrayList<>());

    /**
     * 触发告警。
     *
     * @param sliName   SLI 名称
     * @param actual    实际值
     * @param target    目标值
     * @param severity  严重度
     */
    public void fire(String sliName, double actual, double target, String severity) {
        SLOAlert alert = new SLOAlert(sliName, actual, target, severity, java.time.Instant.now());
        alerts.add(alert);
        log.warn("SLO 告警: sli={}, actual={}, target={}, severity={}", sliName, actual, target, severity);
    }

    /**
     * 获取活跃告警快照。
     *
     * @return 告警列表
     */
    public List<SLOAlert> activeAlerts() {
        return List.copyOf(alerts);
    }

    /**
     * SLO 告警记录。
     *
     * @param sliName   SLI 名称
     * @param actual    实际值
     * @param target    目标值
     * @param severity  严重度
     * @param firedAt   触发时间
     */
    public record SLOAlert(String sliName, double actual, double target, String severity, java.time.Instant firedAt) {
    }
}
