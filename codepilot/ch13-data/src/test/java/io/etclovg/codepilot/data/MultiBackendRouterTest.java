package io.etclovg.codepilot.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MultiBackendRouter 单元测试。
 * 验证 builder 模式构造与基本路由。
 */
@DisplayName("MultiBackendRouter 测试")
class MultiBackendRouterTest {

    @Test
    @DisplayName("builder().build() 返回非 null 实例")
    void builderBuildReturnsNonNull() {
        MultiBackendRouter router = MultiBackendRouter.builder()
                .classifier(new QueryClassifier())
                .backend("SEMANTIC", q -> java.util.List.of("rag-result"))
                .backend("RELATIONAL", q -> java.util.List.of("kg-result"))
                .backend("STRUCTURED", q -> java.util.List.of("sql-result"))
                .mergeStrategy(MergeStrategy.DEDUP_BY_ID)
                .fallback("SEMANTIC")
                .build();

        assertThat(router).isNotNull();
        assertThat(router.mergeStrategy()).isEqualTo(MergeStrategy.DEDUP_BY_ID);
        assertThat(router.fallback()).isEqualTo("SEMANTIC");
        assertThat(router.classifier()).isNotNull();
    }

    @Test
    @DisplayName("route 根据分类结果返回非 null 结果")
    void routeReturnsNonNullResult() {
        MultiBackendRouter router = MultiBackendRouter.builder()
                .classifier(new QueryClassifier())
                .backend("SEMANTIC", q -> java.util.List.of("hit-1", "hit-2"))
                .fallback("SEMANTIC")
                .build();

        assertThat(router.route("some query")).isNotNull();
    }

    @Test
    @DisplayName("保留 select 便捷方法可用")
    void selectRemainsAvailable() {
        MultiBackendRouter router = MultiBackendRouter.builder().build();
        assertThat(router.select(java.util.List.of("a", "b"))).isEqualTo("a");
    }
}
