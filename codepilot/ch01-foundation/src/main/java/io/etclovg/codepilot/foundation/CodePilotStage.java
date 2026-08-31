package io.etclovg.codepilot.foundation;

import io.etclovg.codepilot.core.Layer;

/**
 * CodePilot 流水线阶段枚举。
 * <p>对应书中 Ch01 §1.5 —— CodePilot 代码评审流水线的阶段划分。
 * <p>流水线从目标感知到最终交付共分七个阶段，每个阶段对应 ETCLOVG 中的一个层，
 * 用于在编排与可观测性中统一描述任务进度。
 * <ul>
 *   <li>{@link #INTAKE}      目标感知与任务接入</li>
 *   <li>{@link #PLAN}        规划与任务分解</li>
 *   <li>{@link #EXECUTE}     工具调用与代码执行</li>
 *   <li>{@link #VERIFY}      验证与回归测试</li>
 *   <li>{@link #REVIEW}      评审与质量检查</li>
 *   <li>{@link #GOVERN}      治理与安全审计</li>
 *   <li>{@link #DELIVER}     交付与发布</li>
 * </ul>
 */
public enum CodePilotStage {

    /** 目标感知与任务接入 */
    INTAKE("目标感知", Layer.L, "接入用户请求，识别目标与约束"),
    /** 规划与任务分解 */
    PLAN("规划", Layer.L, "分解任务、生成执行计划"),
    /** 工具调用与代码执行 */
    EXECUTE("执行", Layer.E, "在沙箱中调用工具并执行代码"),
    /** 验证与回归测试 */
    VERIFY("验证", Layer.V, "运行测试、校验结果正确性"),
    /** 评审与质量检查 */
    REVIEW("评审", Layer.V, "代码评审、质量与可读性检查"),
    /** 治理与安全审计 */
    GOVERN("治理", Layer.G, "安全审计、合规与审批"),
    /** 交付与发布 */
    DELIVER("交付", Layer.L, "创建 PR、合并与发布");

    private final String displayName;
    private final Layer layer;
    private final String description;

    CodePilotStage(String displayName, Layer layer, String description) {
        this.displayName = displayName;
        this.layer = layer;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Layer getLayer() {
        return layer;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 判断当前阶段是否位于给定阶段之后。
     *
     * @param other 另一阶段
     * @return 当前阶段序号大于 other 时返回 true
     */
    public boolean isAfter(CodePilotStage other) {
        return this.ordinal() > other.ordinal();
    }
}
