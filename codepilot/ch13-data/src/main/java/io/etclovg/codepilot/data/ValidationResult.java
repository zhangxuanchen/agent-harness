package io.etclovg.codepilot.data;

import java.util.List;

/**
 * 校验结果。
 * <p>对应书中 Ch13 §13.2 —— 数据/工具校验的统一结果结构。
 * <p>描述校验是否通过、发现的问题列表与严重度，供审批网关与部署管理器决策。
 * 由 {@link SafetyValidator#validate} 与 {@code AstSafetyScanner.scan} 产出，
 * 供 {@link ToolManufacturingPipeline} V 层门禁判断是否进入 G 层。
 *
 * @param valid     是否整体通过
 * @param errors    错误列表
 * @param warnings  警告列表
 * @param validator 校验器名称
 */
public record ValidationResult(
        boolean valid,
        List<String> errors,
        List<String> warnings,
        String validator
) {

    /**
     * 构造通过的结果（不指定校验器名称）。
     *
     * @return 通过结果
     */
    public static ValidationResult pass() {
        return new ValidationResult(true, List.of(), List.of(), "default");
    }

    /**
     * 构造通过的结果。
     *
     * @param validator 校验器名称
     * @return 通过结果
     */
    public static ValidationResult pass(String validator) {
        return new ValidationResult(true, List.of(), List.of(), validator);
    }

    /**
     * 构造失败的结果（单条错误信息）。
     *
     * @param message 错误信息
     * @return 失败结果
     */
    public static ValidationResult fail(String message) {
        return new ValidationResult(false, List.of(message), List.of(), "default");
    }

    /**
     * 构造失败的结果。
     *
     * @param validator 校验器名称
     * @param errors    错误列表
     * @return 失败结果
     */
    public static ValidationResult fail(String validator, List<String> errors) {
        return new ValidationResult(false, errors, List.of(), validator);
    }

    /**
     * 是否通过校验。
     *
     * @return 通过返回 true
     */
    public boolean passed() {
        return valid;
    }

    /**
     * 错误数量。
     *
     * @return 数量
     */
    public int errorCount() {
        return errors == null ? 0 : errors.size();
    }
}
