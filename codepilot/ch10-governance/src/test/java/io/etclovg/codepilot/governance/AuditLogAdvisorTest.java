package io.etclovg.codepilot.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * AuditLogAdvisor 单元测试。
 * 聚焦于审计日志查询、完整性验证等核心逻辑。
 */
@DisplayName("审计日志 Advisor 测试")
class AuditLogAdvisorTest {

    private AuditLogAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new AuditLogAdvisor();
    }

    @Nested
    @DisplayName("日志查询测试")
    class LogQuery {

        @Test
        @DisplayName("entryCount 应返回日志条目数")
        void entryCount_returnsCorrectCount() {
            assertThat(advisor.entryCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("recentEntries 应返回指定数量的记录")
        void recentEntries_returnsSpecifiedLimit() {
            // 空日志应返回空列表
            var entries = advisor.recentEntries(10);
            assertThat(entries).isEmpty();
        }

        @Test
        @DisplayName("findBySession 应返回指定会话的记录")
        void findBySession_returnsSessionEntries() {
            // 空日志应返回空列表
            var entries = advisor.findBySession("test-session");
            assertThat(entries).isEmpty();
        }
    }

    @Nested
    @DisplayName("Merkle 树测试")
    class MerkleTree {

        @Test
        @DisplayName("getMerkleRoot 初始应返回 null")
        void getMerkleRoot_initial_returnsNull() {
            assertThat(advisor.getMerkleRoot()).isNull();
        }

        @Test
        @DisplayName("getMerkleRootHistory 初始应返回空列表")
        void getMerkleRootHistory_initial_returnsEmpty() {
            assertThat(advisor.getMerkleRootHistory()).isEmpty();
        }

        @Test
        @DisplayName("forceBuildMerkleTree 不应抛出异常")
        void forceBuildMerkleTree_doesNotThrow() {
            assertThatCode(() -> advisor.forceBuildMerkleTree()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("完整性验证测试")
    class IntegrityCheck {

        @Test
        @DisplayName("verifyIntegrity 应返回完整性报告")
        void verifyIntegrity_returnsReport() {
            AuditLogAdvisor.IntegrityReport report = advisor.verifyIntegrity();
            
            assertThat(report).isNotNull();
            assertThat(report.intact()).isTrue();
            assertThat(report.entryCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("IntegrityReport.summary 应生成可读摘要")
        void integrityReport_summary_containsKeyInfo() {
            AuditLogAdvisor.IntegrityReport report = advisor.verifyIntegrity();
            String summary = report.summary();
            
            assertThat(summary).isNotNull();
            assertThat(summary).contains("审计日志");
        }
    }

    @Nested
    @DisplayName("详细日志配置测试")
    class DetailedLogging {

        @Test
        @DisplayName("setDetailedLogging 应正确设置")
        void setDetailedLogging_setsFlag() {
            assertThatCode(() -> advisor.setDetailedLogging(true)).doesNotThrowAnyException();
            assertThatCode(() -> advisor.setDetailedLogging(false)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("AuditEntry 测试")
    class AuditEntryTest {

        @Test
        @DisplayName("AuditEntry 应正确存储信息")
        void auditEntry_storesCorrectInfo() {
            AuditLogAdvisor.AuditEntry entry = new AuditLogAdvisor.AuditEntry(
                    0, "session-1", Instant.now(), Instant.now(), 100L,
                    "test input", "test-tool", "test output", "prev-hash");
            
            assertThat(entry.index()).isEqualTo(0);
            assertThat(entry.sessionId()).isEqualTo("session-1");
            assertThat(entry.latencyMs()).isEqualTo(100L);
            assertThat(entry.userInput()).isEqualTo("test input");
            assertThat(entry.toolName()).isEqualTo("test-tool");
            assertThat(entry.output()).isEqualTo("test output");
        }

        @Test
        @DisplayName("AuditEntry 应支持 null 工具名")
        void auditEntry_nullToolName_allowed() {
            AuditLogAdvisor.AuditEntry entry = new AuditLogAdvisor.AuditEntry(
                    1, "session-1", Instant.now(), Instant.now(), 50L,
                    "test input", null, "test output", "prev-hash");
            
            assertThat(entry.toolName()).isNull();
        }
    }
}
