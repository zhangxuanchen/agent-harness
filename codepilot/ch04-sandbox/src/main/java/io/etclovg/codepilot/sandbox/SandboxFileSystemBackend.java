package io.etclovg.codepilot.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * 沙箱文件系统后端接口。
 * <p>对应书中 Ch04 §4.3 & §4.7.1 —— 沙箱文件系统的抽象层。
 * <p>继承 {@link FileSystemBackend} 统一契约，将 OverlayFS、Bind mount、Tmpfs 等
 * 不同文件系统后端的差异屏蔽在统一接口之后，
 * 供 {@code OverlayFSSnapshotManager}、{@link CompositeFileSystemAdapter} 等组件调用。
 */
public interface SandboxFileSystemBackend extends FileSystemBackend {

    /**
     * 在沙箱内创建工作目录。
     *
     * @param sandboxId 沙箱实例 ID
     * @param relativePath 相对于工作空间的路径
     * @return 创建后的绝对路径
     */
    Path createWorkDir(String sandboxId, String relativePath);

    /**
     * 列出沙箱内指定目录的文件。
     *
     * @param sandboxId 沙箱实例 ID
     * @param relativePath 相对路径
     * @return 文件列表
     */
    @Override
    default List<String> listFiles(String relativePath) {
        return List.of();
    }

    /**
     * 创建文件系统快照。
     *
     * @param sandboxId 沙箱实例 ID
     * @param snapshotName 快照名称
     * @return 快照标识
     */
    String createSnapshot(String sandboxId, String snapshotName);

    /**
     * 回滚到指定快照。
     *
     * @param sandboxId 沙箱实例 ID
     * @param snapshotId 快照标识
     * @return 回滚成功返回 true
     */
    boolean rollbackToSnapshot(String sandboxId, String snapshotId);

    /**
     * 销毁沙箱的文件系统资源。
     *
     * @param sandboxId 沙箱实例 ID
     */
    void destroy(String sandboxId);
}
