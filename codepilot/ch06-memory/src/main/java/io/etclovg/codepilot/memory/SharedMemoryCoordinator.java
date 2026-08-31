package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多 Agent 共享记忆协调器。
 * 配套仓库教学实现，非 AgentScope 内置。
 *
 * 对应书中 KP 6.6.1 "共享记忆模式（上下文共用）"。
 * 多个 Agent 并行读写同一个记忆库时，如果没有协调，A 写的内容会被 B 覆盖。
 * 协调器提供两个机制：
 *   1. 乐观锁：每条记忆带版本号，写入时检查版本是否匹配，不匹配则拒绝
 *   2. Agent 命名空间分区：每个 Agent 默认写自己的命名空间，跨 Agent 共享时显式发布
 */
@Component
public class SharedMemoryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SharedMemoryCoordinator.class);

    /** 命名空间分隔符：agentId:key → key 归属于某个 Agent */
    private static final String NAMESPACE_SEP = ":";

    /** 每条记忆的版本号表（版本号用于乐观锁冲突检测） */
    private final ConcurrentMap<String, AtomicLong> versionTable = new ConcurrentHashMap<>();

    /** 记忆内容存储（演示用内存 Map，生产环境替换为持久化存储） */
    private final ConcurrentMap<String, Object> memoryStore = new ConcurrentHashMap<>();

    /**
     * 写入共享记忆（带乐观锁检测）。
     *
     * @param agentId    写入 Agent 的 ID
     * @param key        记忆键（在 Agent 命名空间下会自动加前缀）
     * @param value      记忆内容
     * @param version    调用方期望的版本号（-1 表示首次写入，不检查版本）
     * @param publishToAll 是否发布到全局命名空间（true 表示其他 Agent 可以直接读取）
     * @return 写入结果：成功/版本冲突/命名空间权限拒绝
     */
    public WriteResult write(String agentId,
                             String key,
                             Object value,
                             long version,
                             boolean publishToAll) {
        String scopedKey = publishToAll ? key : agentId + NAMESPACE_SEP + key;

        // 乐观锁检查
        AtomicLong verHolder = versionTable.computeIfAbsent(scopedKey, k -> new AtomicLong(0));
        long currentVer = verHolder.get();
        if (version >= 0 && currentVer != version) {
            log.warn("[SharedMem] Agent {} 写 {} 版本冲突: 期望 {} 实际 {}",
                    agentId, scopedKey, version, currentVer);
            return new WriteResult(false, WriteStatus.VERSION_CONFLICT,
                    currentVer, scopedKey, null);
        }

        // 执行写入并自增版本号
        Object previous = memoryStore.put(scopedKey, value);
        long newVersion = verHolder.incrementAndGet();
        log.info("[SharedMem] Agent {} 写 {}: 版本 {} → {}，内容类型 {}",
                agentId, scopedKey, currentVer, newVersion,
                value == null ? "null" : value.getClass().getSimpleName());

        return new WriteResult(true, WriteStatus.OK, newVersion, scopedKey, previous);
    }

    /**
     * 读取共享记忆。
     * 优先从 Agent 自己的命名空间读，fallback 到全局命名空间。
     *
     * @param agentId 读取 Agent 的 ID
     * @param key     记忆键
     * @return 读结果：值 + 当前版本号（下次写入时用这个版本号做乐观锁）
     */
    public ReadResult read(String agentId, String key) {
        // 先读 Agent 私有命名空间
        String scopedKey = agentId + NAMESPACE_SEP + key;
        Object val = memoryStore.get(scopedKey);
        String sourceScope = scopedKey;
        if (val == null) {
            // fallback 到全局命名空间
            val = memoryStore.get(key);
            sourceScope = key;
        }
        long version = versionTable.getOrDefault(sourceScope, new AtomicLong(0)).get();
        log.debug("[SharedMem] Agent {} 读 {} ← {}，版本 {}",
                agentId, key, sourceScope, version);
        return new ReadResult(val, version, sourceScope);
    }

    /**
     * Agent 协作交接时自动清理上下文。
     * Agent A 把工作交给 Agent B 后，调用此方法把关键标记从 A 的命名空间复制到 B 的命名空间，
     * 这样 B 不需要从全局命名空间里慢慢查找。
     */
    public void transferNamespace(String fromAgentId,
                                   String toAgentId,
                                   String... keysToTransfer) {
        for (String key : keysToTransfer) {
            ReadResult r = read(fromAgentId, key);
            if (r.value() != null) {
                // 写入接收方的私有命名空间，版本从 0 开始（全新的记忆）
                write(toAgentId, key, r.value(), -1, false);
                log.info("[SharedMem] 命名空间转移 {} → {}: {}",
                        fromAgentId, toAgentId, key);
            }
        }
    }

    /**
     * 生成 AgentScope WorkspaceManager 兼容的记忆索引行。
     * 把命名空间 + 版本号 + 写入者信息一并写入 MEMORY.md，方便追踪。
     */
    public String buildIndexRow(String key, String memoryPath, String writerAgentId) {
        String scopedKey = writerAgentId + NAMESPACE_SEP + key;
        long ver = versionTable.getOrDefault(scopedKey, new AtomicLong(0)).get();
        return String.format("| %s | %s | v%d | %s | %tF |",
                key, memoryPath, ver, writerAgentId, System.currentTimeMillis());
    }

    /** 写入状态 */
    public enum WriteStatus {
        OK,
        VERSION_CONFLICT,   // 版本号不匹配：其他 Agent 已经改了这条
        NAMESPACE_DENIED    // 命名空间不允许写入
    }

    /**
     * 写入结果。
     * @param success       是否成功
     * @param status        写入状态
     * @param newVersion    写入后的新版本号（冲突时返回当前已有的版本号）
     * @param scopedKey     实际写入的带命名空间键
     * @param previousValue 被替换掉的旧值（首次写入为 null）
     */
    public record WriteResult(
            boolean success,
            WriteStatus status,
            long newVersion,
            String scopedKey,
            Object previousValue
    ) {}

    /**
     * 读取结果。
     * @param value        记忆内容（没有为 null）
     * @param version      当前版本号（下次写入用）
     * @param sourceScope  实际读取的来源命名空间键
     */
    public record ReadResult(
            Object value,
            long version,
            String sourceScope
    ) {}
}
