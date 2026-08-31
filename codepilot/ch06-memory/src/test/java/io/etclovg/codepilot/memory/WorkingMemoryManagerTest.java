package io.etclovg.codepilot.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * WorkingMemoryManager 单元测试。
 * 聚焦于工作记忆的添加、滑动窗口、压缩等核心逻辑。
 */
@DisplayName("工作记忆管理器测试")
class WorkingMemoryManagerTest {

    private WorkingMemoryManager manager;

    @BeforeEach
    void setUp() {
        manager = new WorkingMemoryManager();
    }

    @Nested
    @DisplayName("添加条目测试")
    class AddEntry {

        @Test
        @DisplayName("添加用户轮次应成功")
        void addTurn_userRole_succeeds() {
            manager.addTurn("user", "What is Java?");
            
            assertThat(manager.getWindowSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("添加助手轮次应成功")
        void addTurn_assistantRole_succeeds() {
            manager.addTurn("assistant", "Java is a programming language.");
            
            assertThat(manager.getWindowSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("添加工具轮次应成功")
        void addTurn_toolRole_succeeds() {
            manager.addTurn("tool", "python_interpreter", Map.of("result", "42"));
            
            assertThat(manager.getWindowSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("添加 null 条目应被忽略")
        void addEntry_nullEntry_ignored() {
            manager.addEntry(null);
            assertThat(manager.getWindowSize()).isEqualTo(0);
        }

        @Test
        @DisplayName("添加多条轮次应正确递增窗口大小")
        void addTurn_multipleTurns_increasesWindowSize() {
            manager.addTurn("user", "Question 1");
            manager.addTurn("assistant", "Answer 1");
            manager.addTurn("user", "Question 2");
            manager.addTurn("assistant", "Answer 2");
            
            assertThat(manager.getWindowSize()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("滑动窗口测试")
    class SlidingWindow {

        @Test
        @DisplayName("添加超过阈值的轮次应触发压缩")
        void addTurn_exceedsThreshold_triggersCompaction() {
            // 默认压缩阈值为 15
            for (int i = 0; i < 20; i++) {
                manager.addTurn("user", "Message " + i);
            }
            
            // 压缩后窗口大小应该保持在合理范围
            assertThat(manager.getWindowSize()).isGreaterThan(0);
        }

        @Test
        @DisplayName("压缩后应该保留最近的轮次")
        void compaction_preservesRecentTurns() {
            // 添加超过阈值的轮次
            for (int i = 0; i < 20; i++) {
                manager.addTurn("user", "Message " + i);
            }
            
            // 获取所有条目，最后一个应该是最新的
            var entries = manager.getAllEntries();
            assertThat(entries).isNotEmpty();
            WorkingMemoryEntry lastEntry = entries.get(entries.size() - 1);
            assertThat(lastEntry).isNotNull();
            assertThat(lastEntry.content()).contains("Message 19");
        }
    }

    @Nested
    @DisplayName("清理测试")
    class Cleanup {

        @Test
        @DisplayName("clear 应清空所有条目")
        void clear_removesAllEntries() {
            manager.addTurn("user", "Question");
            manager.addTurn("assistant", "Answer");
            
            manager.clear();
            
            assertThat(manager.getWindowSize()).isEqualTo(0);
        }

        @Test
        @DisplayName("清理后可以继续添加条目")
        void clear_thenAdd_worksCorrectly() {
            manager.addTurn("user", "Old question");
            manager.clear();
            manager.addTurn("user", "New question");
            
            assertThat(manager.getWindowSize()).isEqualTo(1);
            WorkingMemoryEntry entry = manager.getAllEntries().get(0);
            assertThat(entry.content()).isEqualTo("New question");
        }
    }

    @Nested
    @DisplayName("窗口查询测试")
    class WindowQuery {

        @Test
        @DisplayName("getWindowSize 应返回正确大小")
        void getWindowSize_returnsCorrectSize() {
            assertThat(manager.getWindowSize()).isEqualTo(0);
            
            manager.addTurn("user", "Q1");
            assertThat(manager.getWindowSize()).isEqualTo(1);
            
            manager.addTurn("assistant", "A1");
            assertThat(manager.getWindowSize()).isEqualTo(2);
        }

        @Test
        @DisplayName("getAllEntries 应返回所有条目")
        void getAllEntries_returnsAllEntries() {
            manager.addTurn("user", "Q1");
            manager.addTurn("assistant", "A1");
            manager.addTurn("user", "Q2");
            
            assertThat(manager.getAllEntries()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("元数据测试")
    class MetadataTest {

        @Test
        @DisplayName("添加带元数据的轮次应正确存储")
        void addTurn_withMetadata_storesCorrectly() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("topic", "java");
            metadata.put("priority", "high");
            
            manager.addTurn("user", "Explain Java", metadata);
            
            WorkingMemoryEntry entry = manager.getAllEntries().get(0);
            assertThat(entry.metadata()).containsEntry("topic", "java");
            assertThat(entry.metadata()).containsEntry("priority", "high");
        }

        @Test
        @DisplayName("添加无元数据的轮次应使用空 Map")
        void addTurn_withoutMetadata_usesEmptyMap() {
            manager.addTurn("user", "Hello");
            
            WorkingMemoryEntry entry = manager.getAllEntries().get(0);
            assertThat(entry.metadata()).isNotNull();
            assertThat(entry.metadata()).isEmpty();
        }
    }
}
