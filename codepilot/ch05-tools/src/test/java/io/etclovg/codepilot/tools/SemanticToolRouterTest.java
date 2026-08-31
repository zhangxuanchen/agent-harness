package io.etclovg.codepilot.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * SemanticToolRouter 单元测试。
 * 聚焦于核心路由逻辑和语义相似度计算，避免过度依赖 Spring 上下文。
 */
@DisplayName("语义工具路由器测试")
class SemanticToolRouterTest {

    private SemanticToolRouter router;

    @BeforeEach
    void setUp() {
        router = new SemanticToolRouter();
    }

    @Nested
    @DisplayName("基础路由逻辑")
    class BasicRouting {

        @Test
        @DisplayName("空工具列表应返回空结果")
        void route_withEmptyTools_returnsEmptyList() {
            List<String> result = router.route("test", new HashMap<>());
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Null 工具列表应返回空结果")
        void route_withNullTools_returnsEmptyList() {
            List<String> result = router.route("test", null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("工具数量少于 TopK 时应返回全量")
        void route_withFewerToolsThanK_returnsAll() {
            Map<String, String> tools = Map.of(
                    "tool_a", "Description A",
                    "tool_b", "Description B"
            );
            List<String> result = router.route("test", tools, 5);
            assertThat(result).containsExactlyInAnyOrder("tool_a", "tool_b");
        }

        @Test
        @DisplayName("TopK 应被限制在 1-10 之间")
        void route_withInvalidK_clampsValue() {
            Map<String, String> tools = new HashMap<>();
            for (int i = 0; i < 20; i++) {
                tools.put("tool_" + i, "Tool " + i + " description");
            }
            
            // K=100 should clamp to 10
            List<String> result100 = router.route("tool", tools, 100);
            assertThat(result100).hasSize(10);

            // K=0 should clamp to 1
            List<String> result0 = router.route("tool", tools, 0);
            assertThat(result0).hasSize(1);
        }

        @Test
        @DisplayName("应按语义相似度排序返回")
        void route_returnsSortedBySimilarity() {
            Map<String, String> tools = new HashMap<>();
            tools.put("python_interpreter", "Execute Python code");
            tools.put("java_compiler", "Compile Java code");
            tools.put("shell_executor", "Execute shell commands");
            // 添加一个无关工具，确保工具数 > TopK，触发语义检索逻辑
            tools.put("useless_tool", "This tool does nothing related");
            
            List<String> result = router.route("run python script", tools, 3);
            
            // python_interpreter 应该排名第一，因为它与 "python" 和 "execute" 匹配
            assertThat(result).isNotEmpty();
            assertThat(result.get(0)).isEqualTo("python_interpreter");
        }
    }

    @Nested
    @DisplayName("语义相似度计算")
    class SemanticSimilarity {

        @Test
        @DisplayName("查询与描述完全不相关时得分应低")
        void similarity_withUnrelatedText_isLow() {
            double score = router.semanticSimilarity("python script", "db_query", "Query database records");
            assertThat(score).isLessThan(0.3);
        }

        @Test
        @DisplayName("查询与描述高度相关时得分应高")
        void similarity_withRelatedText_isHigh() {
            double score = router.semanticSimilarity("execute python script", "python_interpreter", "Execute Python scripts");
            assertThat(score).isGreaterThan(0.5);
        }

        @Test
        @DisplayName("工具名称匹配应增加分数")
        void similarity_withNameMatch_boostsScore() {
            // 确保描述都不包含查询词，使名字匹配成为主要区分因素
            double scoreWithName = router.semanticSimilarity("python", "python_tool", "Some generic operation");
            double scoreWithoutName = router.semanticSimilarity("python", "java_tool", "Some generic operation");
            
            // 名字里有 "python" 应该得分更高
            assertThat(scoreWithName).isGreaterThan(scoreWithoutName);
        }

        @Test
        @DisplayName("Null 输入应返回 0 分")
        void similarity_withNullInput_returnsZero() {
            double score = router.semanticSimilarity(null, "tool", "desc");
            assertThat(score).isEqualTo(0.0);

            double score2 = router.semanticSimilarity("query", "tool", null);
            assertThat(score2).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("工具注册与管理")
    class ToolManagement {

        @Test
        @DisplayName("注册工具应增加大小")
        void registerTool_increasesSize() {
            int initialSize = router.size();
            router.registerTool("test_tool", "Test description");
            assertThat(router.size()).isEqualTo(initialSize + 1);
        }

        @Test
        @DisplayName("取消注册应减少大小")
        void unregisterTool_decreasesSize() {
            router.registerTool("test_tool", "Test description");
            int sizeBefore = router.size();
            router.unregisterTool("test_tool");
            assertThat(router.size()).isEqualTo(sizeBefore - 1);
        }
    }
}
