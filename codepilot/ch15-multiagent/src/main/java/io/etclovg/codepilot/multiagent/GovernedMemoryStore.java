package io.etclovg.codepilot.multiagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 共享记忆治理：四要素强制的写入/检索路径。对应书中 Ch15 §15.4.5 · 概念示例。
 * <p>写入时强制作用域标签 + 权限校验 + 来源记录；检索时做作用域过滤。
 * 与 §15.2 Agent/Session 分离的关系：
 * <ul>
 *   <li>Agent 无状态，记忆不在 Agent 实例里，统一由 GovernedMemoryStore 管理</li>
 *   <li>AgentSession 持有的是"当前会话历史"，GovernedMemoryStore 持有的是"跨会话长期记忆"</li>
 *   <li>两者通过 sessionId/userId 关联，但生命周期独立</li>
 * </ul>
 */
@Component
public class GovernedMemoryStore {
    private static final Logger log = LoggerFactory.getLogger(GovernedMemoryStore.class);

    private final MemoryBackend backend;           // Redis / 向量库
    private final PermissionChecker permission;    // 端到端权限校验

    public GovernedMemoryStore(MemoryBackend backend, PermissionChecker permission) {
        this.backend = backend;
        this.permission = permission;
    }

    /**
     * 写入路径——四要素强制：
     * 1. 作用域标签（scope）必须四元组齐全，缺一拒绝
     * 2. 端到端权限：校验 agent 是否有权写该作用域
     * 3. 来源追溯：记录 writer / timestamp / source_trace
     * 4. 聚合层：检测同类模式时向上聚合（见 aggregateIfPattern 方法）
     */
    public WriteResult write(MemoryEntry entry) {
        // 要素 1：作用域标签强制
        if (!entry.scope().isComplete()) {  // user_id/agent_id/session_id/app_id 齐全
            return WriteResult.rejected("作用域标签不完整，拒绝写入");
        }
        // 要素 2：端到端权限（写入侧）
        if (!permission.canWrite(entry.writerAgentId(), entry.scope())) {
            return WriteResult.rejected("Agent 无权写入该作用域");
        }
        // 要素 3：来源追溯
        MemoryRecord record = MemoryRecord.builder()
            .entry(entry)
            .writerAgentId(entry.writerAgentId())
            .writtenAt(Instant.now())
            .sourceTrace(entry.sourceContext())  // 基于什么上下文写的
            .build();
        backend.save(record);
        // 要素 4：聚合层——检测同类模式
        aggregateIfPattern(record);
        return WriteResult.ok(record.id());
    }

    /**
     * 检索路径——端到端权限（检索侧）：
     * 即使入库时放行了，检索时仍按 caller 的作用域过滤，
     * 双重保险防止 A 用户读到 B 用户的记忆。
     */
    public List<MemoryRecord> search(String query, MemoryScope callerScope) {
        return backend.search(query).stream()
            .filter(r -> permission.canRead(callerScope, r.scope()))  // 作用域过滤
            .collect(Collectors.toList());
    }

    // ---- 概念示例桩：检测同类模式时向上聚合为组织级结论 ----
    private void aggregateIfPattern(MemoryRecord record) {
        // 生产中：统计同类观察，达阈值后提升为组织级高阶认知
        // 例如三个 Reviewer 都标记同一类安全漏洞 → 提升为"该漏洞模式需全局修复"
        log.debug("[记忆治理] 聚合层检查 record={}", record.id());
    }
}
