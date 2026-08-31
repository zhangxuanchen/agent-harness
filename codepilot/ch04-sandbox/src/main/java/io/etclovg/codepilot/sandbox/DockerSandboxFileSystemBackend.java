package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Docker 沙箱文件系统后端——教学示意实现。
 * <p>对应书中 Ch04 §4.7.1 —— CompositeFilesystem 中 /exec 路径的后端。
 * <p>实现 {@link SandboxFileSystemBackend} 契约，将高风险执行操作的文件
 * 存储在 Docker 容器的 overlay2 文件系统中，与宿主文件系统隔离。
 * 生产实现应调用 Docker API 挂载/卸载容器卷，此处为可测试的内存实现。
 */
@Component
public class DockerSandboxFileSystemBackend implements SandboxFileSystemBackend {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxFileSystemBackend.class);

    private final Path rootPath = Path.of("/var/lib/docker/overlay2");
    private final Map<String, String> store = new ConcurrentHashMap<>();
    private final Map<String, String> snapshots = new ConcurrentHashMap<>();

    @Override
    public FileSystemStorageType getStorageType() {
        return FileSystemStorageType.OVERLAY;
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
        String prefix = relativePath.endsWith("/") ? relativePath : relativePath + "/";
        return store.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .toList();
    }

    @Override
    public Path getRootPath() {
        return rootPath;
    }

    @Override
    public Path createWorkDir(String sandboxId, String relativePath) {
        log.info("[DockerFS] 创建工作目录: sandbox={}, path={}", sandboxId, relativePath);
        return rootPath.resolve(sandboxId).resolve(relativePath);
    }

    @Override
    public String createSnapshot(String sandboxId, String snapshotName) {
        String snapshotId = "snap-" + UUID.randomUUID().toString().substring(0, 8);
        snapshots.put(snapshotId, sandboxId + ":" + snapshotName);
        log.info("[DockerFS] 创建快照: id={}, sandbox={}, name={}", snapshotId, sandboxId, snapshotName);
        return snapshotId;
    }

    @Override
    public boolean rollbackToSnapshot(String sandboxId, String snapshotId) {
        log.info("[DockerFS] 回滚到快照: sandbox={}, snapshot={}", sandboxId, snapshotId);
        return snapshots.containsKey(snapshotId);
    }

    @Override
    public void destroy(String sandboxId) {
        log.info("[DockerFS] 销毁沙箱文件系统: sandbox={}", sandboxId);
        store.clear();
    }
}
