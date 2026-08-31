package io.etclovg.codepilot.behavior;

import java.time.Instant;
import java.util.Map;

/**
 * 单步状态快照——统一状态协议的最小单位。
 * <p>对应书中 Ch03 §3.4.1 —— L 层统一状态协议中各层 checkpoint 的封装。
 *
 * <p>每个 {@code AgentStepState} 记录某一步执行时 E/C/L/O 各层的可恢复快照，
 * 供 {@link AgentStateManager} 汇聚为会话级轨迹，实现断点恢复与 O 层审计。
 *
 * <p>字段对齐 AgentScope 2.0.0 {@code AgentState} 的命名（sessionId, userId, curIter），
 * 区别在于本 record 增加了 step 级的 status 和 checkpoint 数据。
 *
 * @param userId     用户 ID（可为 null，表示匿名会话）
 * @param sessionId  会话唯一标识
 * @param stepIndex  当前步数索引（对齐 AgentState 的 curIter）
 * @param status     步骤状态（PENDING / RUNNING / COMPLETED / FAILED）
 * @param checkpoint 各层 checkpoint 数据（E/C/L/O 四层独立状态汇聚）
 * @param timestamp  状态记录时间
 */
public record AgentStepState(
        String userId,
        String sessionId,
        int stepIndex,
        StepStatus status,
        Map<String, Object> checkpoint,
        Instant timestamp
) {

    /**
     * 步骤状态枚举。
     * <p>对应书中 Ch03 §3.4.1 的 StepStatus 定义。
     */
    public enum StepStatus {
        PENDING,    // 等待执行
        RUNNING,    // 执行中
        COMPLETED,  // 已完成
        FAILED      // 执行失败
    }
}