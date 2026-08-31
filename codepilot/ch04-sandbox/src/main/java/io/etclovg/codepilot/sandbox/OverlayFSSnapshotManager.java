package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * OverlayFS 快照管理器——基于 Linux 内核 OverlayFS 多层 lowerdir 实现快照与回滚。
 * <p>对应书中 Ch04 §4.4.3 —— 沙箱快照与回滚机制。
 *
 * <h2>核心原理：OverlayFS 多层 lowerdir 叠加</h2>
 * <p>OverlayFS 支持多个 lowerdir 叠加（用冒号分隔），上层覆盖下层。
 * 快照的本质是：把当前的 upper 层"冻结"为只读的 lower 层，再开启新的 upper 层。
 * <b>不需要复制任何文件</b>，快照创建是 O(1) 的 rename 操作。
 *
 * <h2>目录结构与快照演进</h2>
 * <pre>
 * /srv/sandbox/
 * ├── base/                          ← 只读基础层（所有沙箱共享）
 │   ├── jdk-21/
 │   └── skills/
 * │
 * └── {sessionId}/
 *     ├── work/                      ← OverlayFS workdir（内核内部用）
 *     ├── merged/                    ← 挂载点（Agent 看到的统一视图）
 *     ├── diff_v1/                   ← 第 1 个 upper 层（已冻结为快照）
 *     ├── diff_v2/                   ← 第 2 个 upper 层（已冻结为快照）
 *     ├── diff_v3/                   ← 当前 upper 层（Agent 修改落这里）
 *     └── snapshot_meta/             ← 快照元数据
 *         ├── snap-aaa.json             {id, label, diffDir: "diff_v1", timestamp}
 *         └── snap-bbb.json             {id, label, diffDir: "diff_v2", timestamp}
 * </pre>
 *
 * <h2>挂载命令演进（快照如何叠加）</h2>
 * <pre>
 * 初始挂载：
 *   mount -t overlay overlay
 *     -o lowerdir=base,upperdir=diff_v1,workdir=work  merged
 *
 * 创建快照 snap-aaa 后（diff_v1 冻结为只读 lower 层）：
 *   mount -t overlay overlay
 *     -o lowerdir=base:diff_v1,upperdir=diff_v2,workdir=work  merged
 *
 * 创建快照 snap-bbb 后（diff_v2 也冻结为只读 lower 层）：
 *   mount -t overlay overlay
 *     -o lowerdir=base:diff_v1:diff_v2,upperdir=diff_v3,workdir=work  merged
 *
 * 回滚到 snap-aaa（丢弃 diff_v2 和 diff_v3，只保留 diff_v1）：
 *   mount -t overlay overlay
 *     -o lowerdir=base:diff_v1,upperdir=diff_v4,workdir=work  merged
 * </pre>
 *
 * <h2>复杂度分析</h2>
 * <ul>
 *   <li><b>创建快照</b>：O(1)——umount + mv rename + mkdir + mount，不复制文件</li>
 *   <li><b>回滚</b>：O(n)——需要删除当前 diff 层（n = 当前 diff 文件数），但不复制快照内容</li>
 *   <li><b>对比重建容器</b>：Docker 冷启动 ~2s + 重新拉代码 + 重新装依赖，回滚仍快 10-50 倍</li>
 * </ul>
 *
 * <h2>使用限制</h2>
 * <ul>
 *   <li>需要 root 权限或 CAP_SYS_ADMIN（mount 命令要求）</li>
 *   <li>创建快照/回滚时需要 umount，沙箱必须暂停（Agent 不能同时在写）</li>
 *   <li>沙箱内若有运行中的子进程，回滚前必须先 kill，否则文件回滚后进程状态不一致</li>
 * </ul>
 */
@Component
public class OverlayFSSnapshotManager {

    private static final Logger log = LoggerFactory.getLogger(OverlayFSSnapshotManager.class);

    private static final String SANDBOX_ROOT = "/srv/sandbox";
    private static final String BASE_LAYER = SANDBOX_ROOT + "/base";

    /** 快照元数据：记录每个快照对应的 diff 层目录名 */
    private final Map<String, Snapshot> snapshots = new HashMap<>();

    /** 每个会话当前 diff 层的版本号（用于生成 diff_v1, diff_v2, ...） */
    private final Map<String, Integer> sessionDiffVersion = new HashMap<>();

    /**
     * 快照元数据记录。
     *
     * @param snapshotId  快照 ID
     * @param label       人类可读标签（如 "改代码前"）
     * @param timestamp   创建时间戳
     * @param sessionId   所属会话 ID
     * @param diffDirName 冻结的 diff 层目录名（如 "diff_v1"）
     */
    public record Snapshot(
            String snapshotId,
            String label,
            long timestamp,
            String sessionId,
            String diffDirName
    ) {}

    // ===== 沙箱生命周期 =====

    /**
     * 初始化并挂载沙箱——创建初始 diff 层并 mount OverlayFS。
     *
     * @param sessionId 沙箱会话 ID
     */
    public void initSandbox(String sessionId) throws IOException, InterruptedException {
        String sessionDir = SANDBOX_ROOT + "/" + sessionId;
        String workDir = sessionDir + "/work";
        String mergedDir = sessionDir + "/merged";
        String diffDir = sessionDir + "/diff_v1";

        Files.createDirectories(Path.of(workDir));
        Files.createDirectories(Path.of(mergedDir));
        Files.createDirectories(Path.of(diffDir));

        sessionDiffVersion.put(sessionId, 1);

        // mount -t overlay overlay
        //   -o lowerdir=$BASE,upperdir=$SESSION/diff_v1,workdir=$SESSION/work  $SESSION/merged
        // 其中 $BASE = /srv/sandbox/base, $SESSION = /srv/sandbox/{sessionId}
        mountOverlay(sessionId, BASE_LAYER, diffDir, workDir, mergedDir);
        log.info("[OverlayFS] 沙箱初始化: sessionId={}, diff=diff_v1", sessionId);
    }

    /**
     * 卸载并清理沙箱（任务结束时调用）。
     */
    public void destroySandbox(String sessionId) throws IOException, InterruptedException {
        String sessionDir = SANDBOX_ROOT + "/" + sessionId;
        String mergedDir = sessionDir + "/merged";

        // 先 umount，再删目录（顺序不能反）
        runCommand("umount", mergedDir);
        deleteRecursively(Path.of(sessionDir));

        sessionDiffVersion.remove(sessionId);
        snapshots.entrySet().removeIf(e -> e.getValue().sessionId().equals(sessionId));
        log.info("[OverlayFS] 沙箱销毁: sessionId={}", sessionId);
    }

    // ===== 快照与回滚（Agent 主要调用） =====

    /**
     * 创建快照：把当前 diff 层"冻结"为只读 lower 层，开启新的空 upper 层。
     * <p>这是 O(1) 操作——只做 umount + mv rename + mkdir + mount，不复制任何文件。
     *
     * <p>调用前提：沙箱内无运行中的子进程（或已暂停），否则 umount 会失败。
     *
     * @param sessionId 沙箱会话 ID
     * @param label     快照标签（如 "改代码前"）
     * @return 快照记录
     */
    public Snapshot createSnapshot(String sessionId, String label) throws IOException, InterruptedException {
        String sessionDir = SANDBOX_ROOT + "/" + sessionId;
        String mergedDir = sessionDir + "/merged";
        String workDir = sessionDir + "/work";

        // 当前 diff 层（即将被冻结为快照）
        int currentVersion = sessionDiffVersion.get(sessionId);
        String currentDiff = sessionDir + "/diff_v" + currentVersion;

        // 新的 diff 层
        int newVersion = currentVersion + 1;
        String newDiff = sessionDir + "/diff_v" + newVersion;

        String snapId = "snap-" + UUID.randomUUID().toString().substring(0, 8);

        // ① umount 当前 OverlayFS（暂停沙箱文件系统）
        runCommand("umount", mergedDir);

        // ② currentDiff 已经是普通目录，无需 rename——它将作为 lower 层被只读挂载
        //    （mv 操作在这里不需要，因为 OverlayFS lower 层就是普通目录）
        // ③ 创建新的空 upper 层
        Files.createDirectories(Path.of(newDiff));

        // ④ 重新 mount：把 base + currentDiff 都作为 lower 层，newDiff 作为新的 upper 层
        //    mount -t overlay overlay
        //      -o lowerdir=$BASE:$SESSION/diff_v{current},upperdir=$SESSION/diff_v{new},workdir=$SESSION/work  $SESSION/merged
        String lowerDirs = BASE_LAYER + ":" + currentDiff;
        mountOverlay(sessionId, lowerDirs, newDiff, workDir, mergedDir);

        sessionDiffVersion.put(sessionId, newVersion);

        Snapshot snapshot = new Snapshot(snapId, label, System.currentTimeMillis(),
                sessionId, "diff_v" + currentVersion);
        snapshots.put(snapId, snapshot);
        log.info("[OverlayFS] 创建快照: id={}, label={}, frozenDiff=diff_v{}, newDiff=diff_v{}",
                snapId, label, currentVersion, newVersion);
        return snapshot;
    }

    /**
     * 回滚到指定快照：丢弃快照之后的所有 diff 层，重新以快照的 diff 层为起点。
     * <p>这是 O(n) 操作——需要删除快照之后的 diff 层（n = 后续 diff 文件数），
     * 但不复制快照内容本身。
     *
     * <p>调用前提：沙箱内无运行中的子进程（或已暂停），否则 umount 会失败。
     *
     * @param snapshotId 要恢复到的快照 ID
     */
    public void restoreSnapshot(String snapshotId) throws IOException, InterruptedException {
        Snapshot snapshot = snapshots.get(snapshotId);
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不存在: " + snapshotId);
        }

        String sessionId = snapshot.sessionId();
        String sessionDir = SANDBOX_ROOT + "/" + sessionId;
        String mergedDir = sessionDir + "/merged";
        String workDir = sessionDir + "/work";
        String snapshotDiff = sessionDir + "/" + snapshot.diffDirName();

        // ① umount 当前 OverlayFS
        runCommand("umount", mergedDir);

        // ② 删除快照之后的所有 diff 层（清理 Agent 在快照之后产生的修改）
        //    这些是版本号大于快照 diff 版本的 diff_v* 目录
        int snapshotVersion = extractVersion(snapshot.diffDirName());
        int currentVersion = sessionDiffVersion.get(sessionId);
        for (int v = snapshotVersion + 1; v <= currentVersion; v++) {
            Path laterDiff = Path.of(sessionDir + "/diff_v" + v);
            deleteRecursively(laterDiff);
        }

        // ③ 创建新的空 upper 层
        int newVersion = currentVersion + 1;
        String newDiff = sessionDir + "/diff_v" + newVersion;
        Files.createDirectories(Path.of(newDiff));

        // ④ 重新 mount：base + 快照的 diff 层 作为 lower，新空目录作为 upper
        //    mount -t overlay overlay
        //      -o lowerdir=$BASE:$SESSION/diff_v{snapshot},upperdir=$SESSION/diff_v{new},workdir=$SESSION/work  $SESSION/merged
        String lowerDirs = BASE_LAYER + ":" + snapshotDiff;
        mountOverlay(sessionId, lowerDirs, newDiff, workDir, mergedDir);

        sessionDiffVersion.put(sessionId, newVersion);

        // ⑤ 清理快照之后创建的所有快照元数据（它们引用的 diff 层已被删除）
        snapshots.entrySet().removeIf(e -> {
            Snapshot s = e.getValue();
            return s.sessionId().equals(sessionId)
                    && extractVersion(s.diffDirName()) > snapshotVersion;
        });

        log.info("[OverlayFS] 回滚到快照: id={}, label={}, restoredDiff={}, newDiff=diff_v{}",
                snapshotId, snapshot.label(), snapshot.diffDirName(), newVersion);
    }

    /**
     * 删除快照（释放磁盘空间）。
     * <p>注意：只能删除最早的快照（没有其他快照依赖它作为 lower 层）。
     * 删除中间快照会导致 OverlayFS lower 层断裂。
     *
     * @param snapshotId 要删除的快照 ID
     */
    public boolean deleteSnapshot(String snapshotId) throws IOException {
        Snapshot snapshot = snapshots.get(snapshotId);
        if (snapshot == null) {
            return false;
        }
        // 安全检查：只能删除最早的快照
        String sessionId = snapshot.sessionId();
        boolean isOldest = snapshots.values().stream()
                .filter(s -> s.sessionId().equals(sessionId))
                .mapToInt(s -> extractVersion(s.diffDirName()))
                .min()
                .orElse(Integer.MAX_VALUE) == extractVersion(snapshot.diffDirName());
        if (!isOldest) {
            throw new IllegalStateException("只能删除最早的快照，删除中间快照会导致 lower 层断裂");
        }

        Path snapshotDiff = Path.of(SANDBOX_ROOT + "/" + sessionId + "/" + snapshot.diffDirName());
        deleteRecursively(snapshotDiff);
        snapshots.remove(snapshotId);
        log.info("[OverlayFS] 删除快照: id={}, diffDir={}", snapshotId, snapshot.diffDirName());
        return true;
    }

    /**
     * 列出指定会话的所有快照。
     */
    public Collection<Snapshot> listSnapshots(String sessionId) {
        return snapshots.values().stream()
                .filter(s -> s.sessionId().equals(sessionId))
                .sorted(Comparator.comparingLong(Snapshot::timestamp))
                .toList();
    }

    /**
     * 在快照保护下执行操作——失败自动回滚（多步任务 / 不可逆操作场景推荐）。
     *
     * <pre>{@code
     * snapshotManager.executeWithRollback(sessionId, "改代码前", () -> {
     *     agent.editCode("UserService.java", newCode);
     *     agent.runTests();  // 失败会自动回滚到改代码前
     *     return testResult;
     * });
     * }</pre>
     *
     * @param sessionId 沙箱会话 ID
     * @param label     快照标签
     * @param action    要执行的操作
     */
    public <T> T executeWithRollback(String sessionId, String label, Supplier<T> action) {
        try {
            Snapshot snapshot = createSnapshot(sessionId, label);
            try {
                return action.get();
            } catch (RuntimeException e) {
                log.warn("[OverlayFS] 执行失败，回滚到快照: id={}, error={}",
                        snapshot.snapshotId(), e.getMessage());
                restoreSnapshot(snapshot.snapshotId());
                throw e;
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("快照操作失败", e);
        }
    }

    // ===== 内部工具方法 =====

    /** 执行 mount 命令挂载 OverlayFS */
    private void mountOverlay(String sessionId, String lowerDirs, String upperDir,
                              String workDir, String mergedDir) throws IOException, InterruptedException {
        // mount -t overlay overlay -o lowerdir=...,upperdir=...,workdir=... merged
        runCommand("mount", "-t", "overlay", "overlay",
                "-o", "lowerdir=" + lowerDirs + ",upperdir=" + upperDir + ",workdir=" + workDir,
                mergedDir);
    }

    /** 执行 shell 命令，失败时抛 IOException */
    private void runCommand(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String error = new String(process.getInputStream().readAllBytes());
            throw new IOException("命令失败 [" + String.join(" ", cmd) + "]: " + error);
        }
    }

    /** 从 "diff_v3" 提取版本号 3 */
    private int extractVersion(String diffDirName) {
        return Integer.parseInt(diffDirName.substring("diff_v".length()));
    }

    /** 递归删除目录（等价于 rm -rf） */
    private void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("删除失败: {}", p, e);
                        }
                    });
        }
    }
}
