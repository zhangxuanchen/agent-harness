package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 回退决策器。对应书中 Ch16 §16.5.1。
 *
 * <p>模型更新或 Canary 检测到统计显著退化后，执行回退决策：
 * 自动将模型版本回退到上一个已验证通过的稳定版本。
 *
 * <p>这是模型静默更新场景的最后一道防线——如果影子流量 + Canary 都漏检了退化，
 * 最终用户反馈的反馈（48h 闭环）仍然可以触发手动回退。
 */
@Component
public class FallbackDecider {

    private static final Logger log = LoggerFactory.getLogger(FallbackDecider.class);

    /**
     * 回退决策结果。
     *
     * @param shouldFallback  是否需要回退
     * @param fallbackVersion 回退目标版本（模型/Agent Bundle 版本号）
     * @param reason         回退原因
     */
    public record Decision(
            boolean shouldFallback,
            String fallbackVersion,
            String reason
    ) {
        public static Decision noop() { return new Decision(false, null, "无退化，无需回退"); }
        public static Decision rollback(String toVersion, String reason) {
            return new Decision(true, toVersion, reason);
        }
    }

    /**
     * 据退化结果决定是否回退到上一稳定版本。
     *
     * @param currentVersion  当前生产版本号
     * @param shadowResult  影子流量对比结果
     * @param alarmSeverity   告警严重度
     * @return 回退决策
     */
    public Decision decide(String currentVersion, ShadowComparisonResult shadowResult,
                            AlarmSeverity alarmSeverity) {
        if (shadowResult.isSignificantlyDegraded(0.03)) {
            log.warn("模型更新退化显著: successDelta={}, hallucinationDelta={}",
                    shadowResult.successDelta(), shadowResult.hallucinationDelta());
            return Decision.rollback(currentVersion, "影子流量对比退化显著: " + shadowResult.reason());
        }
        if (alarmSeverity == AlarmSeverity.P0) {
            log.warn("P0 告警触发自动回退: currentVersion={}", currentVersion);
            return Decision.rollback(currentVersion, "P0 告警触发强制回退");
        }
        return Decision.noop();
    }

    /** 告警严重度枚举。 */
    public enum AlarmSeverity { P0, P1, P2, INFO }
}
