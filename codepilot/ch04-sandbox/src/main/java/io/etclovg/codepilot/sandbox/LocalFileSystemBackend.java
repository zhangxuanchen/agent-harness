package io.etclovg.codepilot.sandbox;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地文件系统后端——教学示意实现。
 * <p>对应书中 Ch04 §4.7.1 —— CompositeFilesystem 中 /workspace 路径的后端。
 * <p>将工作区文件存储在本地磁盘，频繁读写走 SSD（延迟 <1ms）。
 * 生产实现应使用 {@link java.nio.file.Files} API 操作真实文件系统，
 * 此处为可测试的内存实现。
 */
@Component
public class LocalFileSystemBackend implements FileSystemBackend {

    private final Path rootPath = Path.of("/data/agent/workspace");
    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    @Override
    public FileSystemStorageType getStorageType() {
        return FileSystemStorageType.BIND;
    }

    @Override
    public Optional<String> readFile(String relativePath) {
        return Optional.ofNullable(store.get(relativePath));
    }

    @Override
    public boolean writeFile(String relativePath, String content) {
        store.put(relativePath, content);
        return true;
    }

    @Override
    public List<String> listFiles(String relativePath) {
        List<String> files = new ArrayList<>();
        String prefix = relativePath.endsWith("/") ? relativePath : relativePath + "/";
        for (String key : store.keySet()) {
            if (key.startsWith(prefix)) {
                files.add(key);
            }
        }
        return files;
    }

    @Override
    public Path getRootPath() {
        return rootPath;
    }
}
