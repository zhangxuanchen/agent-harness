package io.etclovg.codepilot.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * 文件系统后端接口——所有具体后端（本地/对象存储/沙箱）的统一抽象。
 * <p>对应书中 Ch04 §4.7.1 —— CompositeFilesystem 多后端组合模式中的后端契约。
 * <p>将 OverlayFS、Bind mount、对象存储（OSS）等不同物理后端的差异
 * 屏蔽在统一接口之后，供 {@link CompositeFileSystemAdapter} 按路径路由调用。
 * <p>注意：此接口与 {@link FileSystemStorageType} 枚举互补——
 * 后者描述存储<em>类型</em>（OverlayFS/Bind/Tmpfs/Volume/Network），
 * 本接口描述存储<em>实现</em>（Local/Oss/Sandbox 等具体后端）。
 */
public interface FileSystemBackend {

    /**
     * 获取后端存储类型。
     *
     * @return 存储类型枚举
     */
    FileSystemStorageType getStorageType();

    /**
     * 读取文件内容。
     *
     * @param relativePath 相对于后端根的路径
     * @return 文件内容，不存在返回空
     */
    java.util.Optional<String> readFile(String relativePath);

    /**
     * 写入文件内容。
     *
     * @param relativePath 相对路径
     * @param content      文件内容
     * @return 写入成功返回 true
     */
    boolean writeFile(String relativePath, String content);

    /**
     * 列出指定目录下的文件。
     *
     * @param relativePath 相对目录路径
     * @return 文件路径列表
     */
    List<String> listFiles(String relativePath);

    /**
     * 获取后端根路径（用于日志和调试）。
     *
     * @return 根路径
     */
    Path getRootPath();
}
