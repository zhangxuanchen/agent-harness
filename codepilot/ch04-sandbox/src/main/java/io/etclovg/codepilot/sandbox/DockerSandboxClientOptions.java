package io.etclovg.codepilot.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Docker 沙箱客户端配置。
 * <p>对应书中 Ch04 §4.2.2 & §4.3.1 —— Docker 客户端的连接、安全加固与资源配额参数。
 * <p>同时支持 JavaBean 风格（Spring {@code @ConfigurationProperties} 注入）和
 * Builder 风格（书中 §4.3.1 的链式 API：{@code .cpuCount(1L).memorySizeBytes(...).network("none")}）。
 * <p>三级配额策略（默认/扩展/重型）通过 Builder 链式调用来表达，
 * 类型安全、IDE 自动补全，替代手写 {@code --memory=} / {@code --cpus=} 字符串。
 */
@Configuration
@ConfigurationProperties(prefix = "codepilot.sandbox.docker")
public class DockerSandboxClientOptions {

    private String dockerHost = "unix:///var/run/docker.sock";
    private String image = "ubuntu:22.04";
    private String workspaceRoot = "/workspace";
    private int cpuShares = 1024;
    private long cpuCount = 1L;
    private long memorySizeBytes = 512L * 1024 * 1024;  // 512MB
    private String network = "none";
    private int timeoutSeconds = 30;
    private List<String> networkAliases = new ArrayList<>();
    private boolean privileged = false;
    private List<String> additionalRunArgs = new ArrayList<>();

    // ===== JavaBean getters/setters（Spring 注入用） =====

    public String getDockerHost() { return dockerHost; }
    public void setDockerHost(String dockerHost) { this.dockerHost = dockerHost; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public String getWorkspaceRoot() { return workspaceRoot; }
    public void setWorkspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; }

    public int getCpuShares() { return cpuShares; }
    public void setCpuShares(int cpuShares) { this.cpuShares = cpuShares; }

    public long getCpuCount() { return cpuCount; }
    public void setCpuCount(long cpuCount) { this.cpuCount = cpuCount; }

    public long getMemorySizeBytes() { return memorySizeBytes; }
    public void setMemorySizeBytes(long memorySizeBytes) { this.memorySizeBytes = memorySizeBytes; }

    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public List<String> getNetworkAliases() { return networkAliases; }
    public void setNetworkAliases(List<String> networkAliases) { this.networkAliases = networkAliases; }

    public boolean isPrivileged() { return privileged; }
    public void setPrivileged(boolean privileged) { this.privileged = privileged; }

    public List<String> getAdditionalRunArgs() { return additionalRunArgs; }
    public void setAdditionalRunArgs(List<String> additionalRunArgs) { this.additionalRunArgs = additionalRunArgs; }

    // ===== Fluent Builder 方法（对齐书中 §4.2.2 & §4.3.1 链式 API） =====

    /** 设置基础镜像（对应书中 {@code .image("ubuntu:22.04")}） */
    public DockerSandboxClientOptions image(String image) {
        this.image = image;
        return this;
    }

    /** 设置工作区路径（对应书中 {@code .workspaceRoot("/workspace")}） */
    public DockerSandboxClientOptions workspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
        return this;
    }

    /** 设置 CPU 核数上限（对应书中 {@code .cpuCount(1L)}） */
    public DockerSandboxClientOptions cpuCount(long cpuCount) {
        this.cpuCount = cpuCount;
        return this;
    }

    /** 设置内存上限字节数（对应书中 {@code .memorySizeBytes(512 * 1024 * 1024L)}） */
    public DockerSandboxClientOptions memorySizeBytes(long bytes) {
        this.memorySizeBytes = bytes;
        return this;
    }

    /** 设置网络模式（对应书中 {@code .network("none")} / {@code .network("agent-net")}） */
    public DockerSandboxClientOptions network(String network) {
        this.network = network;
        return this;
    }

    /** 设置执行超时秒数 */
    public DockerSandboxClientOptions timeoutSeconds(int seconds) {
        this.timeoutSeconds = seconds;
        return this;
    }

    /** 追加 Docker 安全加固参数（对应书中 {@code .additionalRunArgs("--read-only", "--cap-drop=ALL", ...)}） */
    public DockerSandboxClientOptions additionalRunArgs(String... args) {
        for (String arg : args) {
            this.additionalRunArgs.add(arg);
        }
        return this;
    }
}
