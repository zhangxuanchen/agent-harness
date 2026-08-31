package io.etclovg.codepilot.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * E2B 云沙箱客户端配置。
 * <p>对应书中 Ch04 §4.2.4 —— E2B（https://e2b.dev）是为 AI Agent 设计的云沙箱服务，
 * 底层使用 Firecracker microVM 提供隔离，按秒计费、API 即用。
 * <p>使用 E2B 后端前必须完成的三项**额外工作**（否则无法启动）：
 * <ol>
 *   <li>登录 <a href="https://e2b.dev">e2b.dev</a> 注册账号，获取 API Key（免费额度有限）</li>
 *   <li>在 E2B 控制台创建 Sandbox Template（可选）：预安装 Python/Node/JDK/Maven 等工具链，
 *       否则每次冷启动都要现场 pip install，浪费计费时间</li>
 *   <li>设置预算告警：E2B 按秒计费，建议在控制台设月度上限 + 用量阈值邮件提醒</li>
 * </ol>
 */
@Configuration
@ConfigurationProperties(prefix = "codepilot.sandbox.e2b")
public class E2BSandboxClientOptions {

    /** E2B API Key（必填）——从 e2b.dev 控制台获取，建议走环境变量 E2B_API_KEY */
    private String apiKey;
    /** Sandbox Template ID（可选，空用 E2B 默认镜像）——预装好工具链可减少冷启动时间 */
    private String templateId;
    /** 沙箱区域（就近部署降低延迟）——us-east-1 / eu-central-1 / ap-northeast-1 */
    private String region = "ap-northeast-1";
    /** 单沙箱最大存活时间（秒）——超过自动终止，防止 runaway 任务无限扣费 */
    private int maxSandboxLifetimeSeconds = 600;
    /** 单次 exec 命令超时（秒） */
    private int timeoutSeconds = 120;
    /** 内存（MB）——E2B 标准实例 512/1024/2048/4096，按规格计费 */
    private int memoryMb = 1024;
    /** CPU 核数——对应 E2B 实例规格 */
    private int cpuCores = 1;
    /** 是否在 idle 时立即销毁沙箱（推荐 true：节省费用） */
    private boolean destroyOnIdle = true;
    /** Idle 判定阈值（秒）——沙箱内无进程活动超过此时间视为 idle */
    private int idleTimeoutSeconds = 60;
    /** 网络访问控制：默认 true（公网访问）——断网设 false 可降低数据外泄风险 */
    private boolean allowInternetAccess = true;

    // ===== JavaBean getters/setters（Spring 注入用） =====
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String v) { this.templateId = v; }
    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }
    public int getMaxSandboxLifetimeSeconds() { return maxSandboxLifetimeSeconds; }
    public void setMaxSandboxLifetimeSeconds(int v) { this.maxSandboxLifetimeSeconds = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
    public int getMemoryMb() { return memoryMb; }
    public void setMemoryMb(int v) { this.memoryMb = v; }
    public int getCpuCores() { return cpuCores; }
    public void setCpuCores(int v) { this.cpuCores = v; }
    public boolean isDestroyOnIdle() { return destroyOnIdle; }
    public void setDestroyOnIdle(boolean v) { this.destroyOnIdle = v; }
    public int getIdleTimeoutSeconds() { return idleTimeoutSeconds; }
    public void setIdleTimeoutSeconds(int v) { this.idleTimeoutSeconds = v; }
    public boolean isAllowInternetAccess() { return allowInternetAccess; }
    public void setAllowInternetAccess(boolean v) { this.allowInternetAccess = v; }

    // ===== Fluent Builder（链式 API） =====
    public E2BSandboxClientOptions apiKey(String key) { this.apiKey = key; return this; }
    public E2BSandboxClientOptions templateId(String id) { this.templateId = id; return this; }
    public E2BSandboxClientOptions region(String r) { this.region = r; return this; }
    public E2BSandboxClientOptions maxLifetimeSeconds(int s) { this.maxSandboxLifetimeSeconds = s; return this; }
    public E2BSandboxClientOptions timeoutSeconds(int s) { this.timeoutSeconds = s; return this; }
    public E2BSandboxClientOptions memoryMb(int mb) { this.memoryMb = mb; return this; }
    public E2BSandboxClientOptions cpuCores(int c) { this.cpuCores = c; return this; }
    public E2BSandboxClientOptions destroyOnIdle(boolean b) { this.destroyOnIdle = b; return this; }
    public E2BSandboxClientOptions idleTimeoutSeconds(int s) { this.idleTimeoutSeconds = s; return this; }
    public E2BSandboxClientOptions allowInternetAccess(boolean b) { this.allowInternetAccess = b; return this; }
}
