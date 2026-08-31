package io.etclovg.codepilot.data;

/**
 * 工具制造流水线阶段标记。
 * <p>对应书中 Ch13 §13.2 —— 五阶段工具制造安全管线（O→Agent→V→G→T）。
 * <p>描述工具候选在流水线各阶段的状态，验证失败标记为
 * {@link #REJECTED_AT_V}，审批驳回标记为 {@link #REJECTED_AT_G}，
 * 通过审批并注册后标记为 {@link #REGISTERED}。
 */
public enum Stage {
    /** 草稿（Agent 生成阶段产出） */
    DRAFT,
    /** V 层验证失败被驳回 */
    REJECTED_AT_V,
    /** G 层审批通过 */
    APPROVED,
    /** T 层注册完成 */
    REGISTERED,
    /** G 层审批驳回 */
    REJECTED_AT_G
}
