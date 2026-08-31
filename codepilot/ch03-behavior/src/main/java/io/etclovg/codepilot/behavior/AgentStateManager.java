package io.etclovg.codepilot.behavior;

import java.util.List;
import java.util.Optional;

/**
 * L 层状态管理接口——统一状态协议的入口。
 * <p>对应书中 Ch03 §3.4.1 —— 各层通过此接口通知 L 层状态变更，
 * L 层作为状态管理中心汇聚 E/C/L/O 四层的独立状态，实现断点恢复。
 *
 * <p>API 风格对齐 AgentScope 2.0.0 的 {@code AgentStateStore}：
 * 使用 (userId, sessionId) 作为存储 key，与 AgentScope 的会话级状态管理保持一致。
 * 与 AgentScope 不同的是，本接口提供 step 级追踪（每一步独立快照），
 * 而 AgentStateStore 仅提供 session 级快照（整个会话的当前状态）。
 *
 * <p>遵循"单一状态源"原则——避免多主写入导致的状态不一致。
 * 实现类如 {@link InMemoryAgentStateRepository} 提供进程内存储；
 * 生产环境可结合 AgentScope AgentStateStore 的持久化能力实现可恢复的 step 级存储。
 */
public interface AgentStateManager {

    /**
     * 各层通过此方法通知 L 层状态变更。
     *
     * @param userId    用户 ID（可为 null，表示匿名会话）
     * @param sessionId 会话 ID
     * @param state     单步状态快照
     */
    void recordState(String userId, String sessionId, AgentStepState state);

    /**
     * 恢复时查询最后已知状态。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return 最后一次记录的状态（若无则 empty）
     */
    Optional<AgentStepState> getLatestState(String userId, String sessionId);

    /**
     * 获取完整执行轨迹（用于 O 层审计和调试）。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return 按步数有序的完整状态列表
     */
    List<AgentStepState> getFullTrace(String userId, String sessionId);
}