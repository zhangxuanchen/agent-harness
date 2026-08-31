package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Daytona 开发环境管理平台沙箱客户端——教学示意实现。
 * <p>对应书中 Ch04 §4.2.5。AgentScope 官方实现通过 Daytona Java SDK
 * 调用 Daytona Server 管理 Workspace（create/start/exec/stop/delete），
 * 核心特色是 Workspace 从 Snapshot 启动——工具链 100% 一致、零安装。
 * <p>使用前的四项**额外工作**见 {@link DaytonaSandboxClientOptions} 类注释：
 * 部署 Server → 创建 Snapshot → CI 重建快照流水线 → 生成 API Key。
 */
@Component
public class DaytonaSandboxClient {

    private static final Logger log = LoggerFactory.getLogger(DaytonaSandboxClient.class);

    public record DaytonaExecResult(
            boolean success,
            String output,
            int exitCode,
            long durationMs,
            String workspaceId,
            String usedSnapshotId
    ) {}

    /**
     * 从指定 Snapshot 创建一个 Workspace（返回 workspaceId）。
     * <p>对齐 Daytona 官方 SDK：{@code daytona.workspace.create({ source: snapshotId })}.
     */
    public String createWorkspaceFromSnapshot(DaytonaSandboxClientOptions options) {
        if (options.getApiKey() == null || options.getApiKey().isBlank()) {
            throw new IllegalStateException(
                "Daytona API Key 为空，请先在 Daytona 控制台或 CLI 生成 Key。" +
                "§4.2.5 使用前需完成 4 项额外工作：部署 Server/创建 Snapshot/CI 重建流水线/生成 API Key。");
        }
        if (options.getSnapshotId() == null || options.getSnapshotId().isBlank()) {
            throw new IllegalStateException(
                "Daytona Snapshot ID 为空——Daytona 的核心价值就是快照共享，" +
                "请先用 CLI 跑 `daytona snapshot create project-foo-v1` 创建快照，再配置 snapshotId。");
        }
        String id = "ws-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[Daytona] 创建 Workspace: id={}, server={}, provider={}, snapshot={}",
                id, options.getServerUrl(), options.getProvider(), options.getSnapshotId());
        log.info("[Daytona]   环境变量: {}", options.getEnvVars());
        log.info("[Daytona]   Git 远端白名单: {}",
                options.getAllowedGitRemotes().isEmpty() ? "(all)" : options.getAllowedGitRemotes());
        log.info("[Daytona]   Idle 自动停止: {}s", options.getIdleStopSeconds());
        return id;
    }

    /** 启动 Workspace（从快照恢复文件系统和已安装进程） */
    public void startWorkspace(String workspaceId) {
        log.info("[Daytona] 启动 Workspace: {}（从 Snapshot 恢复，无需安装工具链）", workspaceId);
    }

    /** 在 Workspace 内执行命令 */
    public DaytonaExecResult exec(String workspaceId, DaytonaSandboxClientOptions options, String command) {
        long start = System.currentTimeMillis();
        log.info("[Daytona] 执行命令: workspace={}, cmd={}", workspaceId, command);
        long duration = System.currentTimeMillis() - start;
        return new DaytonaExecResult(true, "", 0, duration, workspaceId, options.getSnapshotId());
    }

    /** 停止 Workspace（保留磁盘，下次启动仍能看到文件） */
    public void stopWorkspace(String workspaceId) {
        log.info("[Daytona] 停止 Workspace: {}", workspaceId);
    }

    /** 删除 Workspace（彻底释放磁盘） */
    public void deleteWorkspace(String workspaceId) {
        log.info("[Daytona] 删除 Workspace: {}", workspaceId);
    }

    /**
     * 基于现有 Workspace 创建新快照（JDK 升级、依赖变更完成后调用）。
     * <p>对应 Daytona CLI：{@code daytona snapshot create <workspace-id> <new-snapshot-id>}.
     * 生产环境建议在 CI 流水线中调用此方法，自动测试通过后再改 AgentScope 的 snapshotId。
     */
    public String createSnapshotFromWorkspace(String workspaceId, String newSnapshotName) {
        String newSnap = newSnapshotName + "-" + UUID.randomUUID().toString().substring(0, 6);
        log.info("[Daytona] 基于 Workspace {} 创建新 Snapshot: {}", workspaceId, newSnap);
        log.info("[Daytona]   ⚠️  请在 CI 中跑全量单测验证此快照，再改 AgentScope snapshotId。");
        return newSnap;
    }
}
