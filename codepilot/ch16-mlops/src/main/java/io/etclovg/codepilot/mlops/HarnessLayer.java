package io.etclovg.codepilot.mlops;

/**
 * Harness 七层标签。对应书中 Ch16 §16.4.1。
 * <p>统一命名与排序：<b>E-C-T-L-V-O-G</b>（Eval-Context-Tools-Lens-Orchestrate-Verify-Govern）。
 * 事故根因按此顺序逐层排除，用于五步复盘的"根因定位到 Harness 层"步骤；
 * 路线图阶段判定（{@code RoadmapAssessor.determineStage}）按层成熟度门禁。
 */
public enum HarnessLayer {
    /** Eval 评估层 */
    E,
    /** Context 上下文层 */
    C,
    /** Tools 工具层 */
    T,
    /** Lens 视角/编排层 */
    L,
    /** Orchestrate 编排层 */
    O,
    /** Verify 校验层 */
    V,
    /** Govern 治理层 */
    G
}
