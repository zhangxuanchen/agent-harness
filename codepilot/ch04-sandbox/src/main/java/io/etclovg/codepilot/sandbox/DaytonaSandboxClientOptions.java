package io.etclovg.codepilot.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Daytona 沙箱客户端配置。
 * <p>对应书中 Ch04 §4.2.5 —— Daytona 定位于"标准化开发环境管理"而非安全沙箱，
 * 核心价值是**快照共享**：平台团队一次配置好工具链+依赖+环境变量，
 * 以 Snapshot 形式分发，所有 Agent 实例从同一快照启动，彻底消除环境漂移。
 * <p>使用 Daytona 后端前必须完成的四项**额外工作**（否则无法启动）：
 * <ol>
 *   <li>**部署 Daytona Server**（自建服务器或 Daytona Cloud SaaS）：至少 4 核 8GB，
 *       Docker 守护进程 + Daytona Provider（Docker/K8s 二选一）。开源版可自托管，
 *       企业版提供 SSO/RBAC/审计。</li>
 *   <li>**创建 Workspace Snapshot**（最核心步骤）：用 Daytona CLI 或网页进入
 *       一个空环境 → 装 JDK 8/17 → 装 Node 16/20 → 装 Maven/Gradle/npm/pnpm →
 *       配公司私服地址 / DB 连接串环境变量 → 运行 `daytona snapshot create project-foo-v1`。</li>
 *   <li>**CI/CD 集成**：Snapshot 不是一成不变的——升级 JDK 17→21 时要走 CI 流水线
 *       重建快照，跑单元测试全量通过后再改 AgentScope 的 snapshotId。避免"改了快照 Agent 全挂"。</li>
 *   <li>**创建 Daytona API Key**：在 Daytona 控制台或 CLI 生成，配置到 AgentScope
 *       {@code codepilot.sandbox.daytona.api-key} 或环境变量 DAYTONA_API_KEY。</li>
 * </ol>
 */
@Configuration
@ConfigurationProperties(prefix = "codepilot.sandbox.daytona")
public class DaytonaSandboxClientOptions {

    /** Daytona Server 地址（必填）。自托管例：https://daytona.corp.com，SaaS：https://api.daytona.io */
    private String serverUrl = "https://api.daytona.io";
    /** Daytona API Key（必填）——控制台或 CLI 生成 */
    private String apiKey;
    /** Provider 类型：docker 或 kubernetes（必须和 Daytona Server 端一致） */
    private String provider = "docker";
    /** Workspace Snapshot ID（必填，使用 Daytona 价值的核心）——从 snapshot list 取 */
    private String snapshotId;
    /** 工作目录（Agent 代码写在这里）——和快照中的路径保持一致 */
    private String workspaceRoot = "/workspace";
    /** 超时（秒）——单步 exec 最大等待时间 */
    private int timeoutSeconds = 180;
    /** 空闲多久自动 Stop Workspace（秒）——节省资源，-1 表示常驻（CI 场景常用） */
    private int idleStopSeconds = 1800;
    /** 环境变量透传——Agent 实例启动时注入这些变量 */
    private List<String> envVars = new ArrayList<>();
    /** 允许的 Git 远端仓库白名单——Workspace 初始化时只允许拉这些 repo */
    private List<String> allowedGitRemotes = new ArrayList<>();

    // ===== JavaBean getters/setters（Spring 注入用） =====
    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String v) { this.serverUrl = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String v) { this.snapshotId = v; }
    public String getWorkspaceRoot() { return workspaceRoot; }
    public void setWorkspaceRoot(String v) { this.workspaceRoot = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
    public int getIdleStopSeconds() { return idleStopSeconds; }
    public void setIdleStopSeconds(int v) { this.idleStopSeconds = v; }
    public List<String> getEnvVars() { return envVars; }
    public void setEnvVars(List<String> v) { this.envVars = v; }
    public List<String> getAllowedGitRemotes() { return allowedGitRemotes; }
    public void setAllowedGitRemotes(List<String> v) { this.allowedGitRemotes = v; }

    // ===== Fluent Builder =====
    public DaytonaSandboxClientOptions serverUrl(String url) { this.serverUrl = url; return this; }
    public DaytonaSandboxClientOptions apiKey(String k) { this.apiKey = k; return this; }
    public DaytonaSandboxClientOptions provider(String p) { this.provider = p; return this; }
    public DaytonaSandboxClientOptions snapshotId(String id) { this.snapshotId = id; return this; }
    public DaytonaSandboxClientOptions workspaceRoot(String p) { this.workspaceRoot = p; return this; }
    public DaytonaSandboxClientOptions timeoutSeconds(int s) { this.timeoutSeconds = s; return this; }
    public DaytonaSandboxClientOptions idleStopSeconds(int s) { this.idleStopSeconds = s; return this; }
    public DaytonaSandboxClientOptions envVar(String k, String v) { this.envVars.add(k + "=" + v); return this; }
    public DaytonaSandboxClientOptions allowGitRemote(String url) { this.allowedGitRemotes.add(url); return this; }
}
