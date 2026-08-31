package io.etclovg.codepilot.sandbox;

/**
 * 文件系统存储类型枚举。
 * <p>对应书中 Ch04 §4.3 —— 沙箱文件系统的多种实现后端类型。
 * <p>不同后端在隔离性、性能与快照能力上各有取舍，由 {@link FileSystemPolicy}
 * 根据任务风险等级选择。注意：此枚举描述存储<em>类型</em>（OverlayFS/Bind/Tmpfs 等），
 * 与 {@link FileSystemBackend} 接口（描述存储<em>实现</em>）互补。
 * <ul>
 *   <li>{@link #OVERLAY}    OverlayFS，写时复制，支持快照</li>
 *   <li>{@link #BIND}      Bind mount，直接挂载宿主目录</li>
 *   <li>{@link #TMPFS}     内存文件系统，临时高性能</li>
 *   <li>{@link #VOLUME}    Docker 命名卷，持久化</li>
 *   <li>{@link #NETWORK}   网络文件系统（NFS/CIFS），跨节点共享</li>
 * </ul>
 */
public enum FileSystemStorageType {

    /** OverlayFS：写时复制，支持快照与回滚 */
    OVERLAY("OverlayFS", true, true),
    /** Bind mount：直接挂载宿主目录，无隔离 */
    BIND("Bind Mount", false, false),
    /** Tmpfs：内存文件系统，临时高性能 */
    TMPFS("Tmpfs", true, false),
    /** Docker 命名卷：持久化存储 */
    VOLUME("Named Volume", true, true),
    /** 网络文件系统（NFS/CIFS）：跨节点共享 */
    NETWORK("Network FS", false, true);

    private final String displayName;
    private final boolean isolated;
    private final boolean persistent;

    FileSystemStorageType(String displayName, boolean isolated, boolean persistent) {
        this.displayName = displayName;
        this.isolated = isolated;
        this.persistent = persistent;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 是否提供写隔离。
     *
     * @return 隔离后端返回 true
     */
    public boolean isIsolated() {
        return isolated;
    }

    /**
     * 是否支持持久化（容器销毁后数据保留）。
     *
     * @return 持久化后端返回 true
     */
    public boolean isPersistent() {
        return persistent;
    }
}
