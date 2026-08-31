package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多 Agent 协作 Handoff 上下文构建器。
 * <p>对应书中 KP 6.6.1 —— 多 Agent 协作 Handoff 模式。
 * <p>配套仓库教学实现，非 AgentScope 内置。
 *
 * <p>当控制权从一个 Agent 交接给下一个 Agent 时，本构建器从当前 Agent 的
 * 工作记忆中提取结构化结论摘要（而非完整对话历史），避免接收方 Agent 上下文膨胀。
 *
 * <p>摘要包含四段：
 * <ul>
 *   <li><b>COMPLETED</b>——已完成子目标</li>
 *   <li><b>KEY_FINDINGS</b>——关键发现</li>
 *   <li><b>TODO</b>——待办事项</li>
 *   <li><b>CONSTRAINTS</b>——关键约束</li>
 * </ul>
 */
@Component
public class HandoffContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(HandoffContextBuilder.class);

    /** 已完成子目标的关键词 */
    private static final List<String> COMPLETED_KEYWORDS = List.of(
            "完成", "已完成", "已实现", "done", "completed", "finished", "success", "成功"
    );

    /** 待办事项的关键词 */
    private static final List<String> TODO_KEYWORDS = List.of(
            "待办", "需要", "下一步", "接下来", "todo", "next", "pending", "尚未", "未完成", "待处理"
    );

    /** 关键约束的关键词 */
    private static final List<String> CONSTRAINT_KEYWORDS = List.of(
            "约束", "限制", "必须", "不能", "禁止", "constraint", "must", "不要", "要求"
    );

    /** 共享记忆写入版本号生成器（单调递增，用于多 Agent 并发写入冲突检测） */
    private final AtomicLong sharedMemoryVersion = new AtomicLong(0);

    /**
     * 构建交接摘要——从当前 Agent 的工作记忆中提取结论摘要，传递给下一个 Agent。
     *
     * <p>摘要为结构化文本而非完整对话历史，避免接收 Agent 上下文膨胀。
     *
     * @param sessionContext 当前会话上下文（提供目标与工作记忆）
     * @param recentEntries  当前 Agent 最近的工作记忆条目
     * @return 结构化交接摘要文本
     */
    public String buildHandoffSummary(SessionContext sessionContext,
                                      List<WorkingMemoryEntry> recentEntries) {
        log.debug("构建交接摘要: session={}", sessionContext.getSessionId());

        List<String> completed = new ArrayList<>();
        List<String> keyFindings = new ArrayList<>();
        List<String> todos = new ArrayList<>();
        List<String> constraints = new ArrayList<>();

        if (recentEntries != null) {
            for (WorkingMemoryEntry entry : recentEntries) {
                if (entry == null || entry.content() == null || entry.content().isBlank()) {
                    continue;
                }
                classifyEntry(entry.content().trim(), completed, keyFindings, todos, constraints);
            }
        }

        // 工作记忆中的结构化键值也作为关键发现纳入（携带已沉淀的事实）
        Map<String, Object> workingMemory = sessionContext.getWorkingMemory();
        for (Map.Entry<String, Object> wm : workingMemory.entrySet()) {
            keyFindings.add(wm.getKey() + " = " + wm.getValue());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Handoff Summary\n");
        sb.append("session: ").append(sessionContext.getSessionId()).append("\n");
        if (sessionContext.getGoal() != null) {
            sb.append("goal: ").append(sessionContext.getGoal()).append("\n");
        }
        sb.append("generated_at: ").append(Instant.now()).append("\n\n");

        appendSection(sb, "COMPLETED", completed);
        appendSection(sb, "KEY_FINDINGS", keyFindings);
        appendSection(sb, "TODO", todos);
        appendSection(sb, "CONSTRAINTS", constraints);

        log.debug("交接摘要构建完成: completed={}, findings={}, todo={}, constraints={}",
                completed.size(), keyFindings.size(), todos.size(), constraints.size());
        return sb.toString();
    }

    /**
     * 生成共享记忆写入指令。
     * <p>携带 agentId 与单调递增的版本号，用于多 Agent 间的共享记忆并发写入与冲突检测。
     *
     * @param agentId 执行写入的 Agent 标识
     * @param key     共享记忆键
     * @param value   共享记忆值
     * @return 结构化写入指令文本
     */
    public String buildSharedMemoryWrite(String agentId, String key, Object value) {
        long version = sharedMemoryVersion.incrementAndGet();
        String instruction = String.format(
                "[SHARED_MEMORY_WRITE]\nagent_id: %s\nkey: %s\nvalue: %s\nversion: %d\ntimestamp: %s\n[/SHARED_MEMORY_WRITE]",
                agentId, key, value, version, Instant.now()
        );
        log.debug("生成共享记忆写入指令: agent={}, key={}, version={}", agentId, key, version);
        return instruction;
    }

    /**
     * 将单条工作记忆条目分类到对应的摘要段落。
     * <p>使用关键词启发式归类；约束优先级最高，其次待办、已完成，
     * 未命中关键词的条目默认归入 KEY_FINDINGS。
     */
    private void classifyEntry(String content, List<String> completed,
                               List<String> keyFindings, List<String> todos,
                               List<String> constraints) {
        String lower = content.toLowerCase();

        if (containsAny(lower, CONSTRAINT_KEYWORDS)) {
            constraints.add(content);
        } else if (containsAny(lower, TODO_KEYWORDS)) {
            todos.add(content);
        } else if (containsAny(lower, COMPLETED_KEYWORDS)) {
            completed.add(content);
        } else {
            // 默认作为关键发现保留（含 assistant 推理结论、工具返回的事实等）
            keyFindings.add(content);
        }
    }

    /** 追加一个摘要段落，空段落保留标题以保持结构一致。 */
    private void appendSection(StringBuilder sb, String title, List<String> items) {
        sb.append("## ").append(title).append("\n");
        if (items.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (String item : items) {
                sb.append("- ").append(item).append("\n");
            }
        }
        sb.append("\n");
    }

    /** 大小写不敏感的关键词命中检测。 */
    private boolean containsAny(String lowerContent, List<String> keywords) {
        for (String kw : keywords) {
            if (lowerContent.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
