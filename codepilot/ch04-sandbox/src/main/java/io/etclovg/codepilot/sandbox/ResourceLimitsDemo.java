package io.etclovg.codepilot.sandbox;

/**
 * ResourceLimits 完整交互演示。
 * <p>对应书中 Ch04 §4.5.4 —— 从声明到 cgroups 生效的完整流程。
 * <p>
 * 运行方式：在 IDE 中直接运行 main 方法即可查看输出。
 */
public class ResourceLimitsDemo {

    public static void main(String[] args) {
        // ===== 第 1 步：声明 ResourceLimits =====
        ResourceLimits limits = ResourceLimits.PRODUCTION;
        System.out.println("Step 1: 创建 ResourceLimits.PRODUCTION");
        System.out.println("  cpuCores: " + limits.cpuCores() + " → cpu.max");
        System.out.println("  memoryMb: " + limits.memoryMb() + "MB → memory.max");
        System.out.println("  processLimit: " + limits.processLimit() + " → pids.max");
        System.out.println("  networkMbps: " + limits.networkMbps() + " → net_cls.classid");
        System.out.println();

        // ===== 第 2 步：注入 SandboxConfig =====
        SandboxConfig config = SandboxConfig.builder()
            .isolation(SandboxIsolation.DOCKER)
            .resourceLimits(limits)
            .build();
        System.out.println("Step 2: 注入 SandboxConfig");
        System.out.println("  isolation: " + config.getIsolation());
        System.out.println();

        // ===== 第 3 步：框架内部转换 =====
        DockerSandboxClientOptions dockerOptions = toDockerOptions(config);
        System.out.println("Step 3: 转换为 Docker API 参数");
        System.out.println("  cpuCount: " + dockerOptions.getCpuCount());
        System.out.println("  memorySizeBytes: " + dockerOptions.getMemorySizeBytes());
        System.out.println();

        // ===== 第 4 步：调用 Docker API =====
        DockerSandboxClient dockerClient = new DockerSandboxClient();
        String containerId = dockerClient.createContainer(dockerOptions);
        System.out.println("Step 4: 创建容器");
        System.out.println("  containerId: " + containerId);
        System.out.println();

        System.out.println("完成：ResourceLimits → Docker API → cgroups → Linux 内核强制限流");
    }

    /**
     * 将 SandboxConfig 转换为 Docker 配置选项。
     * 实际 AgentScope 会根据 isolation 类型自动选择适配器。
     */
    private static DockerSandboxClientOptions toDockerOptions(SandboxConfig config) {
        ResourceLimits l = config.getResourceLimits();
        return new DockerSandboxClientOptions()
            .cpuCount((long) l.cpuCores())
            .memorySizeBytes((long) l.memoryMb() * 1024 * 1024)
            .network(l.networkMbps() > 0 ? "agent-net" : "none");
    }
}
