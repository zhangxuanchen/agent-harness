package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 告警服务。
 * <p>对应书中 Ch16 §16.5 —— 模型更新退化与影子流量验证退化的告警出口。
 * <p>由 {@link ModelUpdateManager} 在检测到统计显著退化时触发，将告警路由到
 * 告警平台（PagerDuty / 企业 IM / 工单系统）。教学桩仅记录日志，
 * 生产实现对接外部告警通道并按严重度分级路由。
 */
@Component
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);

    /**
     * 触发告警。
     *
     * @param title    告警标题（如 "模型更新退化" / "影子流量验证退化"）
     * @param sourceId 告警源标识（如模型版本 ID）
     * @param payload  附加上下文（{@link EvalResult} / {@link ShadowComparisonResult} 等）
     */
    public void trigger(String title, String sourceId, Object payload) {
        log.warn("[Alarm] {}: source={}, payload={}", title, sourceId, payload);
    }
}
