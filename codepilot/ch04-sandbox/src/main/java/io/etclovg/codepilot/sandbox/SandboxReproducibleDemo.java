package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 可复现执行演示。
 * <p>对应书中 Ch04 §4.1.2 —— 通过「镜像锁定 + 一次性容器 + 只读根文件系统」三支柱，
 * 确保每次任务从位级一致的环境启动——两次执行结果可复现。
 */
@Component
public class SandboxReproducibleDemo {

    private static final Logger log = LoggerFactory.getLogger(SandboxReproducibleDemo.class);

    private final DockerSandboxClient client;

    public SandboxReproducibleDemo(DockerSandboxClient client) {
        this.client = client;
    }

    /** 可复现执行：每次调用都从同一个不可变环境启动 */
    public String runReproducibly(String taskCommand) {
        // 三支柱配置：镜像锁定 + 一次性容器 + 只读 + tmpfs
        DockerSandboxClientOptions opts = new DockerSandboxClientOptions()
            .image("agent-sandbox:v1@sha256:abc123...")  // ① 镜像 digest 锁定版本
            .workspaceRoot("/workspace")
            .timeoutSeconds(60)
            .additionalRunArgs(
                "--rm",                                       // ② 一次性容器：退出即销毁
                "--read-only",                                // ③ 根文件系统只读
                "--tmpfs=/workspace:noexec,nosuid,size=512m", // ③ 工作目录进 tmpfs（内存）
                "--network=none"                              // 断网，避免外部依赖引入随机性
            );

        String cid = client.createContainer(opts);
        client.startContainer(cid);
        try {
            DockerSandboxClient.ExecResult r = client.execCommand(cid, taskCommand);
            if (!r.success()) {
                log.error("任务执行失败: {}", r.output());
            }
            return r.output();
        } finally {
            // 容器销毁 → tmpfs 中的所有写入随内存释放而消失
            // 第二次调用 runReproducibly() 时，环境与第一次完全一致
            client.shutdown(cid);
        }
    }
}