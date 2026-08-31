package io.etclovg.codepilot.sandbox;

import java.util.List;

/**
 * 沙箱配置模板枚举。
 * <p>对应书中 Ch04 §4.1.3 —— 根据任务类型选择不同的沙箱配置。
 * <p>每种 Profile 定义了：
 * <ul>
 *   <li>资源配额（CPU、内存、磁盘、超时）</li>
 *   <li>网络访问策略</li>
 *   <li>安全等级</li>
 *   <li>允许的操作类型</li>
 * </ul>
 */
public enum SandboxProfile {

    /**
     * 代码执行沙箱。
     * <p>用于运行 Python/JS/Shell 代码，需要较高的计算资源，
     * 但网络访问受限，仅允许访问内部包管理器。
     */
    CODE_EXECUTION(
            "代码执行",
            2, 1024, 20, 60,
            NetworkMode.WHITELIST,
            List.of("pypi.org", "npmjs.org", "registry.npmjs.org"),
            SecurityLevel.MEDIUM,
            List.of("python", "node", "bash", "pip", "npm"),
            "agent-sandbox:code-v1"
    ),

    /**
     * 数据分析沙箱。
     * <p>用于处理 CSV/Excel/JSON 数据，需要较大内存，
     * 允许访问数据库和分析服务。
     */
    DATA_ANALYSIS(
            "数据分析",
            4, 4096, 50, 120,
            NetworkMode.WHITELIST,
            List.of("postgres://", "mysql://", "redis://", "api.analytics.com"),
            SecurityLevel.MEDIUM,
            List.of("python", "pandas", "numpy", "sqlite3", "curl"),
            "agent-sandbox:data-v1"
    ),

    /**
     * 浏览器自动化沙箱。
     * <p>用于 Web 爬取和测试，需要浏览器环境，
     * 允许访问互联网但限制某些域名。
     */
    BROWSER_AUTOMATION(
            "浏览器自动化",
            2, 2048, 30, 90,
            NetworkMode.WHITELIST,
            List.of("*.com", "*.org", "*.io"),
            SecurityLevel.LOW,
            List.of("chrome", "playwright", "selenium", "curl"),
            "agent-sandbox:browser-v1"
    ),

    /**
     * 文件操作沙箱。
     * <p>用于文件读写、压缩、转换等操作，
     * 网络访问受限，主要是本地文件系统操作。
     */
    FILE_OPERATION(
            "文件操作",
            1, 512, 100, 30,
            NetworkMode.NONE,
            List.of(),
            SecurityLevel.HIGH,
            List.of("cp", "mv", "tar", "gzip", "unzip", "convert"),
            "agent-sandbox:file-v1"
    ),

    /**
     * 数据库操作沙箱。
     * <p>用于 SQL 查询和数据库维护，
     * 仅允许访问指定的数据库实例。
     */
    DATABASE_OPERATION(
            "数据库操作",
            1, 1024, 10, 30,
            NetworkMode.WHITELIST,
            List.of("postgres://internal-db:5432", "mysql://internal-db:3306"),
            SecurityLevel.HIGH,
            List.of("psql", "mysql", "sqlite3", "redis-cli"),
            "agent-sandbox:db-v1"
    ),

    /**
     * API 调用沙箱。
     * <p>用于调用外部 API，需要网络访问，
     * 但限制只能访问白名单中的 API 端点。
     */
    API_CALLING(
            "API 调用",
            1, 256, 5, 15,
            NetworkMode.WHITELIST,
            List.of("api.github.com", "api.stripe.com", "api.openweathermap.org"),
            SecurityLevel.MEDIUM,
            List.of("curl", "wget", "python"),
            "agent-sandbox:api-v1"
    ),

    /**
     * 高风险操作沙箱。
     * <p>用于涉及系统配置、权限提升等危险操作，
     * 需要严格的安全审计和人工审批。
     */
    HIGH_RISK_OPERATION(
            "高风险操作",
            1, 256, 5, 10,
            NetworkMode.NONE,
            List.of(),
            SecurityLevel.CRITICAL,
            List.of("sudo", "chmod", "chown", "iptables", "systemctl"),
            "agent-sandbox:restricted-v1"
    );

    private final String displayName;
    private final int cpuCores;
    private final int memoryMb;
    private final int diskLimitGb;
    private final int timeoutSeconds;
    private final NetworkMode networkMode;
    private final List<String> allowedHosts;
    private final SecurityLevel securityLevel;
    private final List<String> allowedActions;
    private final String dockerImage;

    SandboxProfile(String displayName, int cpuCores, int memoryMb, int diskLimitGb,
                   int timeoutSeconds, NetworkMode networkMode, List<String> allowedHosts,
                   SecurityLevel securityLevel, List<String> allowedActions, String dockerImage) {
        this.displayName = displayName;
        this.cpuCores = cpuCores;
        this.memoryMb = memoryMb;
        this.diskLimitGb = diskLimitGb;
        this.timeoutSeconds = timeoutSeconds;
        this.networkMode = networkMode;
        this.allowedHosts = allowedHosts;
        this.securityLevel = securityLevel;
        this.allowedActions = allowedActions;
        this.dockerImage = dockerImage;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getCpuCores() {
        return cpuCores;
    }

    public int getMemoryMb() {
        return memoryMb;
    }

    public int getDiskLimitGb() {
        return diskLimitGb;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public NetworkMode getNetworkMode() {
        return networkMode;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public SecurityLevel getSecurityLevel() {
        return securityLevel;
    }

    public List<String> getAllowedActions() {
        return allowedActions;
    }

    public String getDockerImage() {
        return dockerImage;
    }

    /**
     * 根据任务描述推断适合的沙箱 Profile。
     * <p>这是一个简化的规则匹配器，实际生产中应使用 LLM 做更智能的路由。
     *
     * @param taskDescription 任务描述
     * @return 匹配的 Profile
     */
    public static SandboxProfile inferFromTask(String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return FILE_OPERATION;
        }

        String task = taskDescription.toLowerCase();

        if (task.contains("代码") || task.contains("code") || task.contains("执行") || task.contains("execute")
                || task.contains("python") || task.contains("javascript") || task.contains("shell")) {
            return CODE_EXECUTION;
        }
        // 数据库操作必须在数据分析之前检查，因为"数据库"包含"数据"子串
        if (task.contains("数据库") || task.contains("database") || task.contains("sql") || task.contains("mysql")
                || task.contains("postgres") || task.contains("查询")) {
            return DATABASE_OPERATION;
        }
        if (task.contains("数据") || task.contains("data") || task.contains("分析") || task.contains("analysis")
                || task.contains("csv") || task.contains("excel") || task.contains("报表")) {
            return DATA_ANALYSIS;
        }
        if (task.contains("浏览器") || task.contains("browser") || task.contains("网页") || task.contains("web")
                || task.contains("爬取") || task.contains("scrape")) {
            return BROWSER_AUTOMATION;
        }
        if (task.contains("api") || task.contains("接口") || task.contains("调用") || task.contains("request")
                || task.contains("http")) {
            return API_CALLING;
        }
        if (task.contains("删除") || task.contains("delete") || task.contains("rm") || task.contains("格式化")
                || task.contains("系统") || task.contains("system")) {
            return HIGH_RISK_OPERATION;
        }

        return FILE_OPERATION;
    }

    /**
     * 网络访问模式枚举。
     */
    public enum NetworkMode {
        /** 完全断网 */
        NONE,
        /** 仅允许白名单域名 */
        WHITELIST,
        /** 允许内网访问 */
        INTERNAL,
        /** 允许所有网络访问 */
        FULL
    }

    /**
     * 安全等级枚举。
     */
    public enum SecurityLevel {
        /** 低风险，常规操作 */
        LOW,
        /** 中风险，需日志记录 */
        MEDIUM,
        /** 高风险，需审批 */
        HIGH,
        /** 严重风险，需双人审批 */
        CRITICAL
    }
}
