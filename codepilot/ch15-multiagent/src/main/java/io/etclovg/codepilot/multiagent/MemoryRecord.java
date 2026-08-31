package io.etclovg.codepilot.multiagent;

import java.time.Instant;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.4.5：已持久化的记忆记录——
 * 来源追溯三要素：谁写的（writerAgentId）/ 何时写的（writtenAt）/ 基于什么上下文写的（sourceTrace）。
 */
public record MemoryRecord(String id, MemoryEntry entry, String writerAgentId,
                           Instant writtenAt, Object sourceTrace) {

    /** 作用域委托给 entry——检索过滤时直接用 record.scope() */
    public MemoryScope scope() {
        return entry == null ? null : entry.scope();
    }

    public static MemoryRecordBuilder builder() {
        return new MemoryRecordBuilder();
    }

    /** 概念示例桩 builder（生产中由代码生成或手写） */
    public static class MemoryRecordBuilder {
        private MemoryEntry entry;
        private String writerAgentId;
        private Instant writtenAt;
        private Object sourceTrace;

        public MemoryRecordBuilder entry(MemoryEntry e) { this.entry = e; return this; }
        public MemoryRecordBuilder writerAgentId(String w) { this.writerAgentId = w; return this; }
        public MemoryRecordBuilder writtenAt(Instant t) { this.writtenAt = t; return this; }
        public MemoryRecordBuilder sourceTrace(Object s) { this.sourceTrace = s; return this; }

        public MemoryRecord build() {
            return new MemoryRecord(
                "mem-" + System.nanoTime(), entry, writerAgentId, writtenAt, sourceTrace);
        }
    }
}
