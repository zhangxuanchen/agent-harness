package io.etclovg.codepilot.mlops;

import java.time.Instant;
import java.util.Map;

/**
 * 一条可补偿的副作用操作记录。对应书中 Ch16 §16.3.2。
 * <p>写前日志条目：记录有副作用工具调用的完整参数与执行结果，供回滚时逆序补偿。
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 *
 * @param opId      操作 ID
 * @param toolName  工具名（正向操作类型）
 * @param params    完整参数
 * @param result    执行结果（如 ticketId / emailId）
 * @param timestamp 操作时间
 */
public record CompensableOp(
        String opId,
        String toolName,
        Map<String, Object> params,
        Object result,
        Instant timestamp
) {
}
