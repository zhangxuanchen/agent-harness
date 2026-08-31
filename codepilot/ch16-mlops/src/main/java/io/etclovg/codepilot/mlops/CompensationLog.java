package io.etclovg.codepilot.mlops;

import java.time.Instant;
import java.util.List;

/**
 * 补偿日志：记录有副作用的工具调用。对应书中 Ch16 §16.3.2。
 * <p>getSince 返回部署时间点之后的所有副作用操作，供回滚逆序补偿。
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 */
public interface CompensationLog {

    /** 记录一次副作用操作。 */
    void append(CompensableOp op);

    /** 取某时间点之后的所有副作用操作（按时间升序）。 */
    List<CompensableOp> getSince(Instant since);
}
