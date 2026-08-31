package io.etclovg.codepilot.behavior;

/**
 * 跨层信号键枚举——定义 ETCLOVG 七层之间通过 RuntimeContext 传递信号的标准化键名。
 * <p>对应书中 Ch03 §3.4.3 —— 跨层交互的信号约定。
 *
 * <p>使用枚举而非裸字符串的目的：
 * <ul>
 *   <li>编译期检查：IDE 可自动补全，避免拼写错误导致的静默失效</li>
 *   <li>集中管理：所有跨层信号在一处定义，重构时不会遗漏消费方</li>
 *   <li>文档化：每项枚举值自带 Javadoc，标注信号的生产者和消费者</li>
 * </ul>
 *
 * <p>命名约定：{生产者层}_{信号语义}，如 O_COST_ALERT 表示 O 层（可观测）发出的成本告警信号。
 */
public enum LayerSignalKey {

    /**
     * O 层 → L 层：成本告警信号。
     * <p>生产者：O 层（TokenCostTracker 检测到会话成本超标）
     * <p>消费者：L 层（CostAwareOrchestrationMiddleware 读取后触发模型降级）
     */
    O_COST_ALERT,

    /**
     * L 层 → 下游 Middleware：模型降级决策。
     * <p>生产者：L 层（CostAwareOrchestrationMiddleware 在成本超标时写入）
     * <p>消费者：下游 Middleware 链读取，切换模型为降级版本
     */
    L_MODEL_DOWNGRADE,

    /**
     * V 层 → L 层：验证评分信号。
     * <p>生产者：V 层（VerificationMiddleware 在每次验证后写入评分）
     * <p>消费者：L 层（编排层读取评分决定是否重试）
     */
    V_QUALITY_SCORE,

    /**
     * G 层 → T/E/C 层：安全策略变更信号。
     * <p>生产者：G 层（治理层检测到安全策略变更时写入）
     * <p>消费者：T 层、E 层、C 层（读取后调整各自的安全约束）
     */
    G_POLICY_CHANGE
}