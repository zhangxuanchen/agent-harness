package io.etclovg.codepilot.sandbox;

/**
 * 五层防御的配置集成示例——展示如何通过 SandboxConfig 一次性启用全部防御层。
 * <p>对应书中 Ch04 §4.5.3 —— 多层防御的代码落地。
 * <p>执行主体分工（关键区别）：
 * <pre>
 *   第 1、2 层：Java 声明式配置 → 框架翻译为 Docker 参数 → Linux 内核强制执行
 *   第 3、4 层：Java 主动式防御 → SandboxMonitor 在 JVM 中直接执行检测逻辑
 *   第 5 层：流程规范 → 告警接入 On-call 系统，人做最终判断
 * </pre>
 * <p>第 1、2 层的 Java 代码只是告诉框架"我想要什么防御"，
 * 真正的强制执行由 Docker/Linux 内核完成（Java 无法绕过内核安全机制）。
 * 第 3、4 层的 Java 代码直接在 JVM 中运行，主动采集、检测、触发告警。
 */
public class SandboxDefenseDemo {

    /**
     * 构建启用全部五层防御的沙箱配置。
     * <p>
     * 第 1 层（声明式，由 Docker/Linux 执行）：
     *   isolation + fileSystemPolicy → FileSystemPolicy 白名单投影生成挂载清单，
     *   框架翻译为 Docker volume 挂载参数，仅注入 workspace/logs，排除 sessions/secrets。
     * <p>
     * 第 2 层（声明式，由 Docker/Linux 执行）：
     *   seccomp + capDropAll + nonRoot → 框架翻译为：
     *     --security-opt seccomp=...（内核拦截危险 syscall）
     *     --cap-drop ALL（移除所有 Linux capability）
     *     --user 1000（容器内 uid≠0，无法 sudo/setuid）
     *   即使容器被逃逸成功，攻击者仍无法提权。
     * <p>
     * 第 3、4 层（主动式，由 Java 直接执行）：
     *   resourceLimits 作为 SandboxMonitor 的阈值输入，
     *   SandboxMonitor 在 JVM 中持续采集资源快照 → 比对阈值 → 触发告警。
     */
    public SandboxConfig buildDefenseConfig() {
        // 第 1 层：声明文件系统白名单投影策略
        FileSystemPolicy fsPolicy = FileSystemPolicy.builder()
                .writable("/workspace")          // 允许 Agent 读写工作目录
                .readOnly("/config")             // 配置文件只读
                .exclude("/sessions")            // 明确排除敏感目录
                .exclude("/secrets")
                .build();

        // 一次性声明第 1、2 层的全部加固参数
        // 框架内部翻译链路：SandboxConfig → DockerSandboxClient.createContainer(options)
        //   → Docker API 调用 → Linux 内核强制执行隔离/seccomp/cap-drop
        SandboxConfig config = SandboxConfig.builder()
                .isolation(SandboxIsolation.DOCKER)     // 第 1 层：隔离类型
                .fileSystemPolicy(fsPolicy)             // 第 1 层：白名单投影
                .seccomp(true)                          // 第 2 层：内核级 syscall 过滤
                .capDropAll(true)                       // 第 2 层：内核级移除 capabilities
                .nonRoot(true)                          // 第 2 层：内核级非 root 运行
                .resourceLimits(ResourceLimits.DEFAULT) // 第 4 层：Java 级监控阈值
                .networkPolicy(NetworkPolicy.defaultWhitelist())
                .autoDestroy(true)
                .build();

        // 第 3、4 层：SandboxMonitor 独立运行在 JVM 中（纯 Java 实现）
        // 启动后持续：采集资源快照 → 比对阈值 → 触发 CRITICAL/WARNING 告警
        return config;
    }
}
