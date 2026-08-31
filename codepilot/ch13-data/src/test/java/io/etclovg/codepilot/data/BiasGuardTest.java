package io.etclovg.codepilot.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BiasGuard 单元测试。
 * 验证 reserveEdgeCases 返回非 null 实例。
 */
@DisplayName("BiasGuard 测试")
class BiasGuardTest {

    @Test
    @DisplayName("reserveEdgeCases 返回非 null BiasGuard")
    void reserveEdgeCasesReturnsNonNull() {
        List<TrainingPair> pairs = List.of(
                new TrainingPair("q1", "a1"),
                new TrainingPair("q2", "a2"),
                new TrainingPair("q3", "a3")
        );

        BiasGuard guard = BiasGuard.reserveEdgeCases(pairs, 0.2);

        assertThat(guard).isNotNull();
    }

    @Test
    @DisplayName("reserveEdgeCases 对空列表也返回非 null")
    void reserveEdgeCasesHandlesEmptyList() {
        BiasGuard guard = BiasGuard.reserveEdgeCases(List.of(), 0.2);
        assertThat(guard).isNotNull();
    }

    @Test
    @DisplayName("保留 isBiased/recommend 便捷方法可用")
    void legacyMethodsRemainAvailable() {
        BiasGuard guard = new BiasGuard();
        assertThat(guard.isBiased(0.05)).isFalse();
        assertThat(guard.isBiased(0.5)).isTrue();
        assertThat(guard.recommend(0.05)).contains("PASS");
        assertThat(guard.recommend(0.5)).contains("BLOCK");
    }
}
