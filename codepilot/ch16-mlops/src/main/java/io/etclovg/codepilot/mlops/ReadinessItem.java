package io.etclovg.codepilot.mlops;

/**
 * 就绪检查项。对应书中 Ch16 §16.6.1。
 * <p>当前阶段迈向目标阶段前需逐项满足的条件；未满足项（{@code met=false}）汇总为差距。
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 *
 * @param name  检查项名称
 * @param met   是否满足
 * @param detail 说明
 */
public record ReadinessItem(String name, boolean met, String detail) {
    /** 是否满足（谓词友好命名）。 */
    public boolean isMet() {
        return met;
    }
}
