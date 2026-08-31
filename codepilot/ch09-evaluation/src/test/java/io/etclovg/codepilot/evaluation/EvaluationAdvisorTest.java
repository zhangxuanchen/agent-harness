package io.etclovg.codepilot.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * EvaluationAdvisor 单元测试。
 * 聚焦于评分算法和阶段检测等核心逻辑。
 * 
 * <p>使用测试扩展点方法（ForTest 后缀）直接调用内部逻辑，
 * 避免反射访问私有成员。
 */
@DisplayName("评估 Advisor 测试")
class EvaluationAdvisorTest {

    private EvaluationAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new EvaluationAdvisor();
    }

    @Nested
    @DisplayName("评分算法测试")
    class ScoringAlgorithms {

        @Test
        @DisplayName("scoreAccuracy - 相同词汇应得高分")
        void scoreAccuracy_withSameWords_scoresHigh() {
            EvaluationAdvisor.EvaluationSession session = createSession();
            session.referenceAnswer = "hello world from java";
            session.candidateResponse = "hello world from java";

            double score = advisor.scoreDimensionForTest("accuracy", session);
            assertThat(score).isEqualTo(1.0); // 完全匹配
        }

        @Test
        @DisplayName("scoreAccuracy - 部分匹配应得中等分数")
        void scoreAccuracy_withPartialMatch_scoresMedium() {
            EvaluationAdvisor.EvaluationSession session = createSession();
            session.referenceAnswer = "hello world from java python";
            session.candidateResponse = "hello world from java";

            double score = advisor.scoreDimensionForTest("accuracy", session);
            // 参考: 5个词，候选: 4个词，交集: {"hello", "world", "from", "java"} = 4
            // 得分 = 4/5 = 0.8
            assertThat(score).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.01));
        }

        @Test
        @DisplayName("scoreAccuracy - 空引用应得基础分")
        void scoreAccuracy_withEmptyReference_returnsBaseScore() {
            EvaluationAdvisor.EvaluationSession session = createSession();
            session.referenceAnswer = "";
            session.candidateResponse = "some response";

            double score = advisor.scoreDimensionForTest("accuracy", session);
            assertThat(score).isEqualTo(0.5); // 空引用返回基础分
        }

        @Test
        @DisplayName("scoreCompleteness - 长且格式好的响应应得高分")
        void scoreCompleteness_withGoodResponse_scoresHigh() {
            EvaluationAdvisor.EvaluationSession session = createSession();
            session.candidateResponse = "This is a detailed response that provides comprehensive information.\n" +
                    "It has multiple lines with proper formatting.\n" +
                    "The response is well structured and ends with a proper period.";

            double score = advisor.scoreDimensionForTest("completeness", session);
            // 长度>50(+0.4), 有换行(+0.2), 长度>100(+0.2), 以句号结尾(+0.2) = 1.0
            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("scoreCompleteness - 空响应应得零分")
        void scoreCompleteness_withEmptyResponse_returnsZero() {
            EvaluationAdvisor.EvaluationSession session = createSession();
            session.candidateResponse = "";

            double score = advisor.scoreDimensionForTest("completeness", session);
            assertThat(score).isEqualTo(0.0);
        }

        @Test
        @DisplayName("scoreRelevance - 任务相关词汇应得高分")
        void scoreRelevance_withRelevantWords_scoresHigh() {
            EvaluationAdvisor.EvaluationSession session = createSession();
            session.taskDescription = "write a python function to sort a list";
            session.candidateResponse = "write a python function to sort a list efficiently";

            double score = advisor.scoreDimensionForTest("relevance", session);
            // task words (总共8个): write, a, python, function, to, sort, a, list
            // 其中 length > 2 的: write, python, function, sort, list (5个)
            // 候选包含所有8个词汇
            // 得分 = 5/8 = 0.625
            assertThat(score).isEqualTo(0.625);
        }

        @Test
        @DisplayName("scoreSafety - 包含危险词汇应得低分")
        void scoreSafety_withDangerousWords_scoresLow() {
            EvaluationAdvisor.EvaluationSession session = createSession();
            session.candidateResponse = "This contains a password and a token for hack the system";

            double score = advisor.scoreDimensionForTest("safety", session);
            assertThat(score).isEqualTo(0.3); // 包含危险词汇
        }

        @Test
        @DisplayName("scoreSafety - 安全内容应得高分")
        void scoreSafety_withSafeContent_scoresHigh() {
            EvaluationAdvisor.EvaluationSession session = createSession();
            session.candidateResponse = "This is a normal response with no security issues";

            double score = advisor.scoreDimensionForTest("safety", session);
            assertThat(score).isEqualTo(1.0); // 安全内容
        }

        @Test
        @DisplayName("scoreFormatting - 格式化良好的响应应得高分")
        void scoreFormatting_withGoodFormat_scoresHigh() {
            EvaluationAdvisor.EvaluationSession session = createSession();
            session.candidateResponse = "This is well formatted.\nIt is concise.\nNo code blocks.\nShort lines.";

            double score = advisor.scoreDimensionForTest("formatting", session);
            // 不以空格开头(+0.3), 长度<4000(+0.2), 无```(+0.2), 行数<50(+0.3) = 1.0
            assertThat(score).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("阶段检测测试")
    class StageDetection {

        @Test
        @DisplayName("detectStage - 有效的阶段名应正确识别")
        void detectStage_withValidStage_returnsCorrectStage() {
            Map<String, Object> context = new HashMap<>();
            context.put("eval.stage", "EXECUTE");

            EvaluationAdvisor.Stage stage = advisor.detectStageForTest(context);
            assertThat(stage).isEqualTo(EvaluationAdvisor.Stage.EXECUTE);
        }

        @Test
        @DisplayName("detectStage - 无效的阶段名应默认为 ANCHOR")
        void detectStage_withInvalidStage_defaultsToAnchor() {
            Map<String, Object> context = new HashMap<>();
            context.put("eval.stage", "INVALID_STAGE");

            EvaluationAdvisor.Stage stage = advisor.detectStageForTest(context);
            assertThat(stage).isEqualTo(EvaluationAdvisor.Stage.ANCHOR);
        }

        @Test
        @DisplayName("detectStage - 缺少阶段参数应默认为 ANCHOR")
        void detectStage_withoutStage_defaultsToAnchor() {
            Map<String, Object> context = new HashMap<>();

            EvaluationAdvisor.Stage stage = advisor.detectStageForTest(context);
            assertThat(stage).isEqualTo(EvaluationAdvisor.Stage.ANCHOR);
        }

        @Test
        @DisplayName("detectStage - 小写阶段名应正确识别")
        void detectStage_withLowerCase_returnsCorrectStage() {
            Map<String, Object> context = new HashMap<>();
            context.put("eval.stage", "judge");

            EvaluationAdvisor.Stage stage = advisor.detectStageForTest(context);
            assertThat(stage).isEqualTo(EvaluationAdvisor.Stage.JUDGE);
        }
    }

    @Nested
    @DisplayName("默认评分标准测试")
    class DefaultRubric {

        @Test
        @DisplayName("buildDefaultRubric - 应包含所有维度")
        void buildDefaultRubric_containsAllDimensions() {
            Map<String, Object> rubric = advisor.buildDefaultRubricForTest();

            assertThat(rubric).containsKey("accuracy");
            assertThat(rubric).containsKey("completeness");
            assertThat(rubric).containsKey("relevance");
            assertThat(rubric).containsKey("formatting");
            assertThat(rubric).containsKey("safety");
            assertThat(rubric).containsKey("globalPassThreshold");
        }

        @Test
        @DisplayName("buildDefaultRubric - 全局通过阈值应为 0.70")
        void buildDefaultRubric_hasCorrectGlobalThreshold() {
            Map<String, Object> rubric = advisor.buildDefaultRubricForTest();
            assertThat(rubric.get("globalPassThreshold")).isEqualTo(0.70);
        }

        @Test
        @DisplayName("parseThreshold - 应正确解析阈值")
        void parseThreshold_parsesCorrectly() {
            Map<String, Object> rubric = new HashMap<>();
            rubric.put("globalPassThreshold", 0.85);

            double threshold = advisor.parseThresholdForTest(rubric);
            assertThat(threshold).isEqualTo(0.85);
        }

        @Test
        @DisplayName("parseThreshold - 缺少阈值应使用默认值")
        void parseThreshold_withoutThreshold_usesDefault() {
            Map<String, Object> rubric = new HashMap<>();

            double threshold = advisor.parseThresholdForTest(rubric);
            assertThat(threshold).isEqualTo(0.70);
        }
    }

    // ========== 辅助方法 ==========

    private EvaluationAdvisor.EvaluationSession createSession() {
        return new EvaluationAdvisor.EvaluationSession(
                "test-session-" + UUID.randomUUID().toString().substring(0, 8), 
                Instant.now());
    }
}