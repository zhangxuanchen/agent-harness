package io.etclovg.codepilot.foundation;

import java.time.Instant;

/**
 * PDA 执行结果记录：Perceive-Decide-Act 循环的执行结果
 * 对应书中 Ch01 §1.2 —— PDA 闭环模型
 */
public record PdaResult(
        int steps,
        boolean completed,
        String finalContext,
        String failureReason,
        Instant completedAt
) {
    public PdaResult(int steps, boolean completed, String finalContext, String failureReason) {
        this(steps, completed, finalContext, failureReason, Instant.now());
    }

    public boolean hasFailed() {
        return failureReason != null && !failureReason.isBlank();
    }

    public boolean hasSucceeded() {
        return completed && !hasFailed();
    }
}