package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Docker 沙箱客户端——教学示意实现。
 * <p>对应书中 Ch04 §4.2.2 —— 与 Docker 守护进程交互，管理沙箱容器的生命周期。
 * <p>书中引用 AgentScope 的 {@code DockerSandboxClient}（来源：agentscope-harness/.../impl/docker/），
 * 其完整 API 包含 {@code SandboxClient<O>} 工厂接口和 {@code Sandbox} 运行时接口两层抽象。
 * 本类为 CodePilot 配套仓库的简化教学实现，方法命名映射如下：
 * <ul>
 *   <li>书中 {@code client.create(spec, snapshot, options)} → {@link #createContainer(DockerSandboxClientOptions)}</li>
 *   <li>书中 {@code sandbox.start()} → {@link #startContainer(String)}</li>
 *   <li>书中 {@code sandbox.exec(ctx, cmd, timeout)} → {@link #execCommand(String, String)}</li>
 *   <li>书中 {@code sandbox.stop()} → {@link #stopContainer(String)}</li>
 *   <li>书中 {@code sandbox.shutdown()} → {@link #shutdown(String)}</li>
 * </ul>
 * 生产实现应调用 Docker Java API 管理真实容器，此处为无副作用的日志桩。
 */
@Component
public class DockerSandboxClient {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxClient.class);

    /**
     * 容器状态。
     */
    public enum ContainerState {
        CREATED, RUNNING, PAUSED, STOPPED, REMOVED
    }

    /** 执行结果——对齐书中 ExecResult 的简化教学版 */
    public record ExecResult(boolean success, String output, int exitCode, long durationMs) {
        /** 执行状态枚举——对齐书中 ExecResult.Status */
        public enum Status { SUCCESS, TIMEOUT, ERROR }
    }

    /**
     * 创建容器（对应书中 {@code client.create(workspaceSpec, snapshotSpec, options)}）。
     *
     * @param options Docker 配置选项
     * @return 容器 ID
     */
    public String createContainer(DockerSandboxClientOptions options) {
        String containerId = "container-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[DockerSandbox] 创建容器: id={}, image={}, cpu={}, memory={}MB, network={}",
                containerId, options.getImage(), options.getCpuCount(),
                options.getMemorySizeBytes() / (1024 * 1024), options.getNetwork());
        return containerId;
    }

    /**
     * 启动容器（对应书中 {@code sandbox.start()}——初始化/恢复工作区）。
     *
     * @param containerId 容器 ID
     */
    public void startContainer(String containerId) {
        log.info("[DockerSandbox] 启动容器: {}", containerId);
    }

    /**
     * 在容器内执行命令（对应书中 {@code sandbox.exec(ctx, command, timeoutSeconds)}）。
     *
     * @param containerId 容器 ID
     * @param command     要执行的命令
     * @return 执行结果
     */
    public ExecResult execCommand(String containerId, String command) {
        long start = System.currentTimeMillis();
        log.info("[DockerSandbox] 执行命令: container={}, cmd={}", containerId, command);
        long duration = System.currentTimeMillis() - start;
        return new ExecResult(true, "", 0, duration);
    }

    /**
     * 停止容器（对应书中 {@code sandbox.stop()}——持久化快照，不销毁）。
     *
     * @param containerId 容器 ID
     */
    public void stopContainer(String containerId) {
        log.info("[DockerSandbox] 停止容器: {}", containerId);
    }

    /**
     * 关闭并移除容器（对应书中 {@code sandbox.shutdown()}——释放容器资源）。
     *
     * @param containerId 容器 ID
     */
    public void shutdown(String containerId) {
        log.info("[DockerSandbox] 关闭并移除容器: {}", containerId);
    }

    /**
     * 移除容器。
     *
     * @param containerId 容器 ID
     */
    public void removeContainer(String containerId) {
        log.info("[DockerSandbox] 移除容器: {}", containerId);
    }

    /**
     * 获取容器状态。
     *
     * @param containerId 容器 ID
     * @return 容器状态
     */
    public ContainerState getState(String containerId) {
        return ContainerState.STOPPED;
    }
}
