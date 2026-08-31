package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 评估用例生成器。
 * <p>对应书中 Ch16 §16.8 —— 评估数据集的自动生成。
 * <p>基于生产日志、规约或 LLM 生成评估用例，扩充回归测试集，
 * 供评估流水线使用。
 */
@Component
public class EvalCaseGenerator {

    private static final Logger log = LoggerFactory.getLogger(EvalCaseGenerator.class);

    /**
     * 生成评估用例。
     *
     * @param scenario 场景描述
     * @param count    数量
     * @return 用例列表
     */
    public List<String> generate(String scenario, int count) {
        log.info("生成评估用例: scenario={}, count={}", scenario, count);
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> "case-" + scenario + "-" + i)
                .toList();
    }

    /**
     * 从事故场景与根因生成回归用例（书中 §16.4.1 Step 4 调用）。
     *
     * @param errorScenario 错误场景描述
     * @param rootCause     根因
     * @return 可重复执行的评估用例列表
     */
    public List<EvalCase> generateFrom(String errorScenario, PostmortemReport.RootCause rootCause) {
        log.info("生成回归用例: scenario={}, rootLayer={}", errorScenario, rootCause.getLayer());
        return List.of(EvalCase.simple(
                "case-" + errorScenario,
                java.util.Map.of("scenario", errorScenario, "decisionPoint", rootCause.getDescription()),
                List.of("成功率=1", "幻觉标记=0", "层=" + rootCause.getLayer())));
    }
}
