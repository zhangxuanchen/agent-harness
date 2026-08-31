package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AgentScope 五维隔离实战演示。
 * <p>对应书中 Ch04 §4.1.1 —— 一个 DockerSandboxClientOptions 对象承载全部安全加固参数，
 * DockerSandboxClient 负责翻译为容器创建参数——四步完成隔离执行。
 */
@Component
public class SandboxIsolationDemo {

    private static final Logger log = LoggerFactory.getLogger(SandboxIsolationDemo.class);

    private final DockerSandboxClient client;

    public SandboxIsolationDemo(DockerSandboxClient client) {
        this.client = client;
    }

    /** 演示：五维隔离配置 → 创建容器 → 执行任务 → 销毁 */
    public void demonstrate() {
        // Step 1: 声明安全需求（五维隔离集中配置）
        DockerSandboxClientOptions opts = new DockerSandboxClientOptions()
            .image("agent-sandbox:secure-v1")              // 预构建最小化镜像
            .workspaceRoot("/workspace")                    // ① 文件系统：限定工作目录
            .cpuCount(1L)                                   // ④ 资源：CPU 硬上限
            .memorySizeBytes(512L * 1024 * 1024L)           // ④ 资源：内存硬上限
            .network("agent-net")                           // ② 网络：走代理白名单
            .timeoutSeconds(30)                             // ④ 资源：执行超时
            .additionalRunArgs(                             // ①③⑤ 一次性注入全部加固参数
                "--read-only",                              // ① 文件系统只读
                "--tmpfs=/tmp:noexec,nosuid,size=256m",     // ① /tmp 独立且不可执行
                "--cap-drop=ALL",                           // ③ 系统调用：丢弃所有 capability
                "--security-opt=no-new-privileges",         // ③ 禁止特权提升
                "--user=1000:1000",                         // ⑤ 用户：非 root 运行
                "--pids-limit=50"                           // ④ 进程数硬上限
            );

        // Step 2: 创建容器（DockerSandboxClient 内部翻译配置 → Docker 参数）
        String cid = client.createContainer(opts);          // 一行创建，五维隔离全部生效
        client.startContainer(cid);

        // Step 3: Agent 在隔离环境中执行任务
        DockerSandboxClient.ExecResult result = client.execCommand(cid, "python task.py");
        if (!result.success()) {
            // 超时/错误 → 容器内进程已被 cgroup 硬终止，宿主不受影响
            log.error("任务执行失败: {}", result.output());
        }

        // Step 4: 销毁容器，释放资源
        client.shutdown(cid);
    }
}