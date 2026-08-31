package io.etclovg.codepilot.multiagent;

/**
 * MAST 14 种协调失败模式，按三类根因分组。对应书中 Ch15 §15.5.2 · 概念示例。
 * <p>数据来源：Cemri et al., "Why Do Multi-Agent LLM Systems Fail?",
 * NeurIPS 2025（arXiv:2503.13657），1,642 条执行记录的观测频率。
 * prevalence 字段为该模式在记录中的占比（%）。
 *
 * <p>三类根因：FC1 系统设计（44.2%）+ FC2 Agent 间失配（32.3%）= 76.5%，
 * 验证了"多 Agent 失败主要源于接口与规范断裂，而非单个 Agent 出错"。
 */
public enum MastFailureMode {
    // ---- FC1 系统设计问题（44.2%）----
    STEP_REPETITION(15.7),          // FM-1.3 步骤重复（最高）
    UNSURE_OF_TERMINATION(12.4),    // FM-1.5 不知终止条件
    DISOBEY_TASK_SPEC(11.8),        // FM-1.1 违背任务规范
    LOSS_OF_HISTORY(2.8),           // FM-1.4 丢失会话历史
    DISOBEY_ROLE_SPEC(1.5),         // FM-1.2 违背角色规范

    // ---- FC2 Agent 间失配（32.3%）----
    REASONING_ACTION_MISMATCH(13.2),// FM-2.6 推理-行动失配（第二高）
    TASK_DERAILMENT(7.4),           // FM-2.3 任务脱轨
    NO_CLARIFICATION(6.8),          // FM-2.2 未请求澄清
    CONVERSATION_RESET(2.2),        // FM-2.1 对话重置
    IGNORED_PEER_INPUT(1.9),        // FM-2.5 忽略他方输入
    INFO_WITHHOLDING(0.85),         // FM-2.4 信息隐瞒

    // ---- FC3 任务验证（23.5%）----
    INCORRECT_VERIFICATION(9.1),    // FM-3.3 错误验证
    NO_VERIFICATION(8.2),           // FM-3.2 无/不完整验证
    PREMATURE_TERMINATION(6.2);     // FM-3.1 过早终止

    /** 该模式在 MAST 1,642 条记录中的观测占比（%） */
    public final double prevalence;

    MastFailureMode(double prevalence) {
        this.prevalence = prevalence;
    }
}
