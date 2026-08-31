package io.etclovg.codepilot.sandbox;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对象存储（OSS）文件系统后端——教学示意实现。
 * <p>对应书中 Ch04 §4.7.1 —— CompositeFilesystem 中 /shared 路径的后端。
 * <p>将不变的共享知识库（skills/、knowledge/）存储在对象存储（如阿里云 OSS），
 * 成本低（相比本地磁盘降 80%），适合读多写少的共享数据。
 * 生产实现应调用 OSS SDK，此处为可测试的内存实现。
 */
@Component
public class OssFileSystemBackend implements FileSystemBackend {

    private final Path rootPath = Path.of("bucket://agent-skills");
    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    @Override
    public FileSystemStorageType getStorageType() {
        return FileSystemStorageType.NETWORK;
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
