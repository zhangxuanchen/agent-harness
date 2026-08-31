package io.etclovg.codepilot.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryClassifier 单元测试。
 * 验证 classify 返回非 null 且为合法标签。
 */
@DisplayName("QueryClassifier 测试")
class QueryClassifierTest {

    @Test
    @DisplayName("classify 返回非 null 标签")
    void classifyReturnsNonNull() {
        QueryClassifier classifier = new QueryClassifier();
        String label = classifier.classify("上个月华南区的退货率是多少");
        assertThat(label).isNotNull();
    }

    @Test
    @DisplayName("classify 桩实现默认返回 SEMANTIC")
    void classifyReturnsSemanticByDefault() {
        QueryClassifier classifier = new QueryClassifier();
        String label = classifier.classify("任意查询");
        assertThat(label).isEqualTo(QueryClassifier.SEMANTIC);
    }

    @Test
    @DisplayName("三个标签常量定义正确")
    void labelConstantsAreDefined() {
        assertThat(QueryClassifier.SEMANTIC).isEqualTo("SEMANTIC");
        assertThat(QueryClassifier.RELATIONAL).isEqualTo("RELATIONAL");
        assertThat(QueryClassifier.STRUCTURED).isEqualTo("STRUCTURED");
    }
}
