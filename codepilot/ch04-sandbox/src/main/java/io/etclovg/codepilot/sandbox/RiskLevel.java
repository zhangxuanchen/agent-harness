package io.etclovg.codepilot.sandbox;

/**
 * 风险等级枚举。
 * <p>对应书中 Ch04 §4.4.2 —— 沙箱任务的风险分级。
 * <p>用于在沙箱分配时决定安全加固级别、复用策略与审批要求，
 * 与 {@link SandboxProfile.SecurityLevel} 配合使用。
 * <ul>
 *   <li>{@link #LOW}      低风险：常规读写、查询操作</li>
 *   <li>{@link #MEDIUM}   中风险：代码执行、网络调用</li>
 *   <li>{@link #HIGH}     高风险：系统配置、权限变更</li>
 *   <li>{@link #CRITICAL} 严重风险：不可逆操作、生产数据修改</li>
 * </ul>
 */
public enum RiskLevel {

    /** 低风险：常规读写、查询操作 */
    LOW("低风险", 1, false),
    /** 中风险：代码执行、网络调用 */
    MEDIUM("中风险", 2, false),
    /** 高风险：系统配置、权限变更 */
    HIGH("高风险", 3, true),
    /** 严重风险：不可逆操作、生产数据修改 */
    CRITICAL("严重", 4, true);

    private final String label;
    private final int weight;
    private final boolean approvalRequired;

    RiskLevel(String label, int weight, boolean approvalRequired) {
        this.label = label;
        this.weight = weight;
        this.approvalRequired = approvalRequired;
    }

    public String getLabel() {
        return label;
    }

    public int getWeight() {
        return weight;
    }

    /**
     * 是否需要人工审批才能执行。
     *
     * @return 高危及以上返回 true
     */
    public boolean isApprovalRequired() {
        return approvalRequired;
    }

    /**
     * 从字符串解析风险等级，无法识别时默认 {@link #MEDIUM}。
     *
     * @param value 风险等级字符串
     * @return 对应枚举值
     */
    public static RiskLevel fromString(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM;
        }
        try {
            return RiskLevel.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MEDIUM;
        }
    }
}
