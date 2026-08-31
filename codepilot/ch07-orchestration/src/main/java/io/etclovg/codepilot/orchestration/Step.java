package io.etclovg.codepilot.orchestration;

/**
 * 步骤接口。
 * <p>对应书中 Ch07 §7.2 —— 编排流程中的可执行步骤抽象。
 */
public interface Step {

    /**
     * 获取步骤名称。
     */
    String getName();

    /**
     * 执行步骤。
     *
     * @param context 执行上下文
     * @return 执行结果
     */
    StepResult execute(java.util.Map<String, Object> context);

    /**
     * 步骤结果。
     */
    record StepResult(
            boolean success,
            String message,
            java.util.Map<String, Object> outputs
    ) {
        public static StepResult ok(String message) {
            return new StepResult(true, message, java.util.Map.of());
        }

        public static StepResult fail(String message) {
            return new StepResult(false, message, java.util.Map.of());
        }
    }
}