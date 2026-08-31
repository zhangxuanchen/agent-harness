package io.etclovg.codepilot.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Docker 沙箱加固配置。
 * <p>对应书中 Ch04 §4.2.2 & §4.7.2 —— 非 root + seccomp + cap-drop + 资源限制。
 * <p>同时支持两种使用方式：
 * <ul>
 *   <li>Spring Bean 模式（{@code @ConfigurationProperties} 从 application.yml 注入）</li>
 *   <li>Builder 模式（{@link #builder()} 链式构造，对齐书中 §4.7.2 最小配置示例）</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "codepilot.sandbox")
public class SandboxConfig {

    /** 沙箱镜像，如 {@code agent-sandbox:v3.2} */
    private String image = "agent-sandbox:v3.2";

    /** CPU 核心数上限 */
    private double cpuLimit = 1.0;

    /** 内存上限（MB） */
    private int memoryLimitMb = 512;

    /** 磁盘上限（GB） */
    private int diskLimitGb = 10;

    /** 单步超时（秒） */
    private int timeoutSeconds = 30;

    /** 网络模式：none(断网)/internal(容器网络)/whitelist(白名单代理) */
    private String networkMode = "whitelist";

    /** 允许访问的外部域名列表（仅 networkMode=whitelist 时生效） */
    private String[] allowedHosts = {"api.stripe.com", "api.github.com"};

    /** 是否启用非 root 用户 */
    private boolean nonRoot = true;

    /** 是否启用 seccomp 安全策略 */
    private boolean seccomp = true;

    /** 是否 cap-drop-all（移除全部 Linux capabilities） */
    private boolean capDropAll = true;

    // ===== Builder 模式扩展字段（对齐书中 §4.7.2 最小配置） =====

    /** 隔离级别（Builder 模式） */
    private SandboxIsolation isolation = SandboxIsolation.DOCKER;

    /** 网络策略（Builder 模式） */
    private NetworkPolicy networkPolicy;

    /** 文件系统策略（Builder 模式） */
    private FileSystemPolicy fileSystemPolicy;

    /** 资源限制（Builder 模式） */
    private ResourceLimits resourceLimits;

    /** 任务结束是否自动销毁沙箱 */
    private boolean autoDestroy = true;

    // ===== JavaBean getters/setters（Spring 注入用） =====

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public double getCpuLimit() { return cpuLimit; }
    public void setCpuLimit(double cpuLimit) { this.cpuLimit = cpuLimit; }

    public int getMemoryLimitMb() { return memoryLimitMb; }
    public void setMemoryLimitMb(int memoryLimitMb) { this.memoryLimitMb = memoryLimitMb; }

    public int getDiskLimitGb() { return diskLimitGb; }
    public void setDiskLimitGb(int diskLimitGb) { this.diskLimitGb = diskLimitGb; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getNetworkMode() { return networkMode; }
    public void setNetworkMode(String networkMode) { this.networkMode = networkMode; }

    public String[] getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(String[] allowedHosts) { this.allowedHosts = allowedHosts; }

    public boolean isNonRoot() { return nonRoot; }
    public void setNonRoot(boolean nonRoot) { this.nonRoot = nonRoot; }

    public boolean isSeccomp() { return seccomp; }
    public void setSeccomp(boolean seccomp) { this.seccomp = seccomp; }

    public boolean isCapDropAll() { return capDropAll; }
    public void setCapDropAll(boolean capDropAll) { this.capDropAll = capDropAll; }

    public SandboxIsolation getIsolation() { return isolation; }
    public void setIsolation(SandboxIsolation isolation) { this.isolation = isolation; }

    public NetworkPolicy getNetworkPolicy() { return networkPolicy; }
    public void setNetworkPolicy(NetworkPolicy networkPolicy) { this.networkPolicy = networkPolicy; }

    public FileSystemPolicy getFileSystemPolicy() { return fileSystemPolicy; }
    public void setFileSystemPolicy(FileSystemPolicy fileSystemPolicy) { this.fileSystemPolicy = fileSystemPolicy; }

    public ResourceLimits getResourceLimits() { return resourceLimits; }
    public void setResourceLimits(ResourceLimits resourceLimits) { this.resourceLimits = resourceLimits; }

    public boolean isAutoDestroy() { return autoDestroy; }
    public void setAutoDestroy(boolean autoDestroy) { this.autoDestroy = autoDestroy; }

    // ===== Fluent Builder（对齐书中 §4.7.2 SandboxConfig.builder()...build()） =====

    /**
     * 创建 Builder。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * SandboxConfig 的 Fluent Builder。
     * <p>对应书中 §4.7.2 最小配置示例：
     * <pre>{@code
     * SandboxConfig config = SandboxConfig.builder()
     *     .isolation(SandboxIsolation.DOCKER)
     *     .networkPolicy(NetworkPolicy.defaultWhitelist())
     *     .fileSystemPolicy(FileSystemPolicy.writable("/workspace").readOnly("/"))
     *     .resourceLimits(ResourceLimits.DEFAULT)
     *     .autoDestroy(true)
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private final SandboxConfig config = new SandboxConfig();

        public Builder isolation(SandboxIsolation isolation) {
            config.isolation = isolation;
            return this;
        }

        public Builder networkPolicy(NetworkPolicy networkPolicy) {
            config.networkPolicy = networkPolicy;
            return this;
        }

        public Builder fileSystemPolicy(FileSystemPolicy fileSystemPolicy) {
            config.fileSystemPolicy = fileSystemPolicy;
            return this;
        }

        public Builder resourceLimits(ResourceLimits resourceLimits) {
            config.resourceLimits = resourceLimits;
            return this;
        }

        public Builder autoDestroy(boolean autoDestroy) {
            config.autoDestroy = autoDestroy;
            return this;
        }

        public Builder image(String image) {
            config.image = image;
            return this;
        }

        public Builder cpuLimit(double cpuLimit) {
            config.cpuLimit = cpuLimit;
            return this;
        }

        public Builder memoryLimitMb(int memoryLimitMb) {
            config.memoryLimitMb = memoryLimitMb;
            return this;
        }

        public Builder timeoutSeconds(int timeoutSeconds) {
            config.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder networkMode(String networkMode) {
            config.networkMode = networkMode;
            return this;
        }

        public Builder nonRoot(boolean nonRoot) {
            config.nonRoot = nonRoot;
            return this;
        }

        public Builder seccomp(boolean seccomp) {
            config.seccomp = seccomp;
            return this;
        }

        public Builder capDropAll(boolean capDropAll) {
            config.capDropAll = capDropAll;
            return this;
        }

        public SandboxConfig build() {
            return config;
        }
    }
}
