package io.etclovg.codepilot.memory;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 配套仓库教学骨架（对应书中 KP 6.2.2），非 AgentScope 框架内置。
 * <p>
 * 维护 MEMORY.md 索引文件：写新记忆时追加一行钩子 "- [name](file.md) — description"，
 * 命中 200 行 / 25KB 硬上限则告警提醒用户整理索引。
 * <p>
 * 本类提供最小可跑的文件操作实现，生产环境如需以下能力可独立扩展：
 * <ul>
 *   <li>钩子按时间/类型/作用域分组排序，而不是简单追加</li>
 *   <li>增量重写（替换已有同名条目的 description）</li>
 *   <li>与版本控制集成：MEMORY.md 变更自动生成 PR</li>
 * </ul>
 */
@Component
public class MemoryIndexManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexManager.class);

    /** MEMORY.md 索引硬上限：超过 200 行或 25KB 任一阈值触发告警 */
    static final int MAX_LINES = 200;
    static final int MAX_BYTES = 25 * 1024;

    private final EpisodicKnowledgeStore episodicKnowledgeStore;
    private Path memoryIndexPath;

    public MemoryIndexManager(EpisodicKnowledgeStore episodicKnowledgeStore) {
        this.episodicKnowledgeStore = episodicKnowledgeStore;
    }

    @PostConstruct
    public void init() {
        memoryIndexPath = episodicKnowledgeStore.getMemoryRoot().resolve("MEMORY.md");
        try {
            if (!Files.exists(memoryIndexPath)) {
                Files.createDirectories(memoryIndexPath.getParent());
                Files.writeString(memoryIndexPath,
                        "# Memory Index\n\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
        } catch (IOException e) {
            log.warn("[MemoryIndex] 初始化 MEMORY.md 失败: {}", e.getMessage());
        }
    }

    /**
     * 往 MEMORY.md 追加一行钩子。若已存在同名条目则不重复写（基于 name 做去重）。
     */
    public synchronized void appendIndexLine(String name, String fileName, String description) {
        try {
            String line = String.format("- [%s](%s) — %s%n", name, fileName,
                    description == null ? "" : description.replace("\n", " ").trim());
            String existing = Files.exists(memoryIndexPath)
                    ? Files.readString(memoryIndexPath, StandardCharsets.UTF_8) : "";
            String key = String.format("-[%s](", name);
            // 去掉空白的简化比较，减少因为 description 变化导致误判已存在
            if (existing.replace(" ", "").contains(key.replace(" ", ""))) {
                log.debug("[MemoryIndex] 条目已存在，跳过追加: {}", name);
                return;
            }
            Files.writeString(memoryIndexPath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // 硬上限告警
            List<String> allLines = Files.readAllLines(memoryIndexPath, StandardCharsets.UTF_8);
            int sizeBytes = (int) Files.size(memoryIndexPath);
            if (allLines.size() > MAX_LINES || sizeBytes > MAX_BYTES) {
                log.warn("[MemoryIndex] MEMORY.md 超过上限: lines={}/{}, bytes={}/{}。" +
                                "建议整理索引，否则 System Prompt 索引常驻区域会膨胀。",
                        allLines.size(), MAX_LINES, sizeBytes, MAX_BYTES);
            }
        } catch (IOException e) {
            log.warn("[MemoryIndex] 写入索引失败: name={}, err={}", name, e.getMessage());
        }
    }

    /** 返回当前索引条目数（用于探针诊断） */
    public int currentLineCount() {
        try {
            return Files.exists(memoryIndexPath)
                    ? Files.readAllLines(memoryIndexPath, StandardCharsets.UTF_8).size() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** 暴露索引路径给其他组件（如 fresh 快照检查） */
    public Path getMemoryIndexPath() {
        return memoryIndexPath;
    }
}
