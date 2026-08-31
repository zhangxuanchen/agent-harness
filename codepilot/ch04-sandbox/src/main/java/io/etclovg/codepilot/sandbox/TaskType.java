package io.etclovg.codepilot.sandbox;

/**
 * 任务类型枚举。
 * <p>对应书中 Ch04 §4.3.1 —— 沙箱配置选择的任务分类。
 * <p>用于在分配沙箱时推断适合的 {@link SandboxProfile} 与 {@link ResourceLimits}，
 * 是 {@code SandboxProfile.inferFromTask} 的结构化输入。
 * <ul>
 *   <li>{@link #CODE_EXECUTION}    代码执行</li>
 *   <li>{@link #DATA_ANALYSIS}     数据分析</li>
 *   <li>{@link #BROWSER_AUTOMATION} 浏览器自动化</li>
 *   <li>{@link #FILE_OPERATION}    文件操作</li>
 *   <li>{@link #DATABASE_OPERATION} 数据库操作</li>
 *   <li>{@link #API_CALLING}       API 调用</li>
 *   <li>{@link #HIGH_RISK_OPERATION} 高风险操作</li>
 * </ul>
 */
public enum TaskType {

    /** 代码执行：Python/JS/Shell 等 */
    CODE_EXECUTION("代码执行", RiskLevel.MEDIUM),
    /** 数据分析：CSV/Excel/JSON 处理 */
    DATA_ANALYSIS("数据分析", RiskLevel.MEDIUM),
    /** 浏览器自动化：Web 爬取与测试 */
    BROWSER_AUTOMATION("浏览器自动化", RiskLevel.LOW),
    /** 文件操作：读写、压缩、转换 */
    FILE_OPERATION("文件操作", RiskLevel.LOW),
    /** 数据库操作：SQL 查询与维护 */
    DATABASE_OPERATION("数据库操作", RiskLevel.HIGH),
    /** API 调用：外部接口请求 */
    API_CALLING("API 调用", RiskLevel.MEDIUM),
    /** 高风险操作：系统配置、权限提升 */
    HIGH_RISK_OPERATION("高风险操作", RiskLevel.CRITICAL);

    private final String displayName;
    private final RiskLevel defaultRisk;

    TaskType(String displayName, RiskLevel defaultRisk) {
        this.displayName = displayName;
        this.defaultRisk = defaultRisk;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取该任务类型的默认风险等级。
     *
     * @return 默认风险等级
     */
    public RiskLevel getDefaultRisk() {
        return defaultRisk;
    }

    /**
     * 是否为高风险任务类型。
     *
     * @return 默认风险等级为 HIGH 或 CRITICAL 时返回 true
     */
    public boolean isHighRisk() {
        return defaultRisk.getWeight() >= RiskLevel.HIGH.getWeight();
    }
}
