package io.etclovg.codepilot.multiagent;

import java.util.List;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.4.5：共享记忆存储后端（Redis / 向量库）。
 */
public interface MemoryBackend {
    void save(MemoryRecord record);
    List<MemoryRecord> search(String query);
}
