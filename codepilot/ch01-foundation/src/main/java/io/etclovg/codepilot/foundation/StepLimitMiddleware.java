package io.etclovg.codepilot.foundation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 步骤限制中间件：限制 Agent 执行的最大步骤数
 * 对应书中 Ch01 §1.3 —— Agent 资源限制
 */
@Component("stepLimit")
public class StepLimitMiddleware {

    private static final Logger log = LoggerFactory.getLogger(StepLimitMiddleware.class);

    private final int maxSteps;
    private final int warningThreshold;

    public StepLimitMiddleware() {
        this(20, 15);
    }

    public StepLimitMiddleware(int maxSteps, int warningThreshold) {
        this.maxSteps = maxSteps;
        this.warningThreshold = warningThreshold;
    }

    public StepCheckResult check(int currentSteps) {
        if (currentSteps >= maxSteps) {
            log.warn("[StepLimitMiddleware] 步骤数已达上限: current={}, max={}",
                    currentSteps, maxSteps);
            return new StepCheckResult(false, true,
                    String.format("步骤数(%d)已达到上限(%d)，强制终止", currentSteps, maxSteps));
        }

        if (currentSteps >= warningThreshold) {
            log.warn("[StepLimitMiddleware] 步骤数接近上限: current={}, warning={}, max={}",
                    currentSteps, warningThreshold, maxSteps);
            return new StepCheckResult(true, true,
                    String.format("步骤数(%d)接近上限(%d)，请注意收敛", currentSteps, maxSteps));
        }

        return new StepCheckResult(true, false,
                String.format("步骤数(%d)在安全范围内(%d)", currentSteps, maxSteps));
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public int getWarningThreshold() {
        return warningThreshold;
    }

    public record StepCheckResult(
            boolean allowed, boolean warning, String message
    ) {}
}