package io.etclovg.codepilot.multiagent;

/**
 * 第三层：人工审批结果——高风险操作触发 Human-in-the-Loop。对应书中 Ch15 §15.3.2 · 概念示例。
 */
public enum ApprovalResult { APPROVED, DENIED, ASK }
