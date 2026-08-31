package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 组合文件系统适配器——多后端路径路由。
 * <p>对应书中 Ch04 §4.6.1 —— CompositeFilesystem（多后端组合，三层架构第二层）。
 * <p>将 Agent 看到的统一路径树路由到不同的物理文件系统后端：
 * <ul>
 *   <li>{@code /workspace} → {@link LocalFileSystemBackend}（本地磁盘，频繁读写）</li>
 *   <li>{@code /shared}    → {@link OssFileSystemBackend}（对象存储，共享知识库）</li>
 *   <li>{@code /exec}      → {@link SandboxFileSystemBackend}（Docker 容器，高风险执行）</li>
 * </ul>
 * Agent 代码看到的是一个统一的文件树，底层是三套独立存储——
 * 频繁修改的工作文件走本地（快）、不变的共享知识走对象存储（便宜）、
 * 高风险执行走 Docker（安全）。路由通过 {@link #resolve(String)} 的 O(1) 前缀查表完成。
 */
@Component
public class CompositeFileSystemAdapter {

    private static final Logger log = LoggerFactory.getLogger(CompositeFileSystemAdapter.class);

    /** 路径前缀 → 后端映射（LinkedHashMap 保证按插入顺序匹配最长前缀） */
    private final Map<String, FileSystemBackend> mounts = new LinkedHashMap<>();

    /**
     * 默认构造——注入三种后端并注册路径映射。
     * <p>对应书中 §4.7.1 的构造方式：
     * <pre>{@code
     * mounts.put("/workspace", local);   // 工作区 → 本地磁盘
     * mounts.put("/shared", oss);        // 共享知识 → 对象存储
     * mounts.put("/exec", sandbox);      // 高风险执行 → Docker
     * }</pre>
     */
    public CompositeFileSystemAdapter(
            LocalFileSystemBackend local,
            OssFileSystemBackend oss,
            SandboxFileSystemBackend sandbox) {
        mounts.put("/workspace", local);
        mounts.put("/shared", oss);
        mounts.put("/exec", sandbox);
        log.info("CompositeFileSystemAdapter 初始化: mounts={}", mounts.keySet());
    }

    /**
     * 根据路径解析对应的文件系统后端。
     * <p>遍历 mounts，返回第一个匹配前缀的后端（O(1) 前缀查表）。
     *
     * @param path 文件路径（如 /workspace/src/Main.java）
     * @return 匹配的后端
     * @throws SandboxException 如果没有后端匹配该路径
     */
    public FileSystemBackend resolve(String path) {
        if (path == null || path.isBlank()) {
            throw new SandboxException(null, SandboxException.ErrorType.FILESYSTEM_ERROR,
                    "路径为空，无法路由到文件系统后端");
        }
        for (Map.Entry<String, FileSystemBackend> entry : mounts.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                log.debug("路径路由: path={} -> backend={}", path, entry.getValue().getStorageType());
                return entry.getValue();
            }
        }
        throw new SandboxException(null, SandboxException.ErrorType.FILESYSTEM_ERROR,
                "No backend for: " + path);
    }

    /**
     * 读取文件——先路由到后端，再调用后端读取。
     *
     * @param path 完整路径（如 /workspace/src/Main.java）
     * @return 文件内容，不存在返回空
     */
    public Optional<String> readFile(String path) {
        FileSystemBackend backend = resolve(path);
        String relativePath = stripPrefix(path);
        return backend.readFile(relativePath);
    }

    /**
     * 写入文件——先路由到后端，再调用后端写入。
     *
     * @param path    完整路径
     * @param content 文件内容
     * @return 写入成功返回 true
     */
    public boolean writeFile(String path, String content) {
        FileSystemBackend backend = resolve(path);
        String relativePath = stripPrefix(path);
        return backend.writeFile(relativePath, content);
    }

    /**
     * 列出指定目录下的文件。
     *
     * @param path 目录路径
     * @return 文件路径列表
     */
    public List<String> listFiles(String path) {
        FileSystemBackend backend = resolve(path);
        String relativePath = stripPrefix(path);
        return backend.listFiles(relativePath);
    }

    /**
     * 获取所有已注册的路径挂载点。
     *
     * @return 不可变映射
     */
    public Map<String, FileSystemBackend> getMounts() {
        return Map.copyOf(mounts);
    }

    /** 从完整路径中去除挂载前缀，得到相对于后端根的路径 */
    private String stripPrefix(String path) {
        for (String prefix : mounts.keySet()) {
            if (path.startsWith(prefix)) {
                String relative = path.substring(prefix.length());
                return relative.startsWith("/") ? relative.substring(1) : relative;
            }
        }
        return path;
    }
}
