package io.etclovg.codepilot.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 检查点管理器。
 * <p>对应书中 Ch07 §7.6 —— 在关键节点保存执行状态，支持故障恢复。
 */
@Component
public class CheckpointManager {

    private static final Logger log = LoggerFactory.getLogger(CheckpointManager.class);

    private final Map<String, Checkpoint> checkpoints = new ConcurrentHashMap<>();

    /**
     * 检查点记录。
     */
    public record Checkpoint(
            String checkpointId,
            String taskId,
            String description,
            Map<String, Object> state,
            long timestamp
    ) {}

    /**
     * 创建检查点。
     */
    public Checkpoint createCheckpoint(String taskId, String description, Map<String, Object> state) {
        String id = "cp-" + UUID.randomUUID().toString().substring(0, 8);
        Checkpoint cp = new Checkpoint(id, taskId, description, state, System.currentTimeMillis());
        checkpoints.put(id, cp);
        log.info("[Checkpoint] 创建检查点: id={}, task={}", id, taskId);
        return cp;
    }

    /**
     * 从检查点恢复。
     */
    public Optional<Checkpoint> restore(String checkpointId) {
        return Optional.ofNullable(checkpoints.get(checkpointId));
    }

    /**
     * 列出任务的所有检查点。
     */
    public List<Checkpoint> listByTask(String taskId) {
        return checkpoints.values().stream()
                .filter(c -> c.taskId().equals(taskId))
                .sorted(Comparator.comparingLong(Checkpoint::timestamp))
                .toList();
    }

    /**
     * 删除检查点。
     */
    public boolean delete(String checkpointId) {
        return checkpoints.remove(checkpointId) != null;
    }
}