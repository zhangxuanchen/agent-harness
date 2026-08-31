package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 流水线阶段评估器。
 * <p>对应书中 Ch16 §16.3 —— 评估流水线各阶段的阶段评估。
 * <p>对流水线中每个阶段（生成、评估、门禁、部署）单独打分，
 * 定位流水线瓶颈与薄弱环节。
 */
@Component
public class StageEvaluator {

    private static final Logger log = LoggerFactory.getLogger(StageEvaluator.class);

    /**
     * 评估指定阶段。
     *
     * @param stageName 阶段名称
     * @param passed    是否通过
     * @param durationMs 耗时（毫秒）
     * @return 阶段评估结果
     */
    public StageResult evaluate(String stageName, boolean passed, long durationMs) {
        double score = passed ? 1.0 : 0.0;
        log.info("阶段评估: stage={}, passed={}, duration={}ms", stageName, passed, durationMs);
        return new StageResult(stageName, passed, score, durationMs);
    }

    /**
     * 评估指定 Harness 层在项目中的建设成熟度。
     * <p>对应书中 §16.6 {@code evaluator.evaluate(layer, projectId)}——对七个 Harness 层
     * （E-C-T-L-V-O-G）逐一打分（0-100），供 {@link RoadmapAssessor} 判定当前所处阶段
     * 与距离下一阶段的差距。教学桩按层返回固定分数，生产实现应基于检查清单
     * （评估集规模、可观测性覆盖率、安全规则数等）量化打分。
     *
     * @param layer     Harness 层
     * @param projectId 项目 ID
     * @return 成熟度评分（0-100）
     */
    public int evaluate(HarnessLayer layer, String projectId) {
        int score = switch (layer) {
            case T -> 70; // 工具层通常最先建设
            case L -> 65; // 编排层
            case E -> 55; // 评估层
            case V -> 45; // 校验层
            case G -> 35; // 治理层
            case O -> 25; // 可观测层
            case C -> 20; // 上下文/成本层
        };
        log.info("Harness 层成熟度评估: project={}, layer={}, score={}", projectId, layer, score);
        return score;
    }

    /**
     * 汇总各阶段评估。
     *
     * @param results 各阶段结果
     * @return 整体通过率
     */
    public double overallPassRate(List<StageResult> results) {
        if (results == null || results.isEmpty()) {
            return 0.0;
        }
        long passed = results.stream().filter(StageResult::passed).count();
        return (double) passed / results.size();
    }

    /**
     * 阶段评估结果。
     *
     * @param stageName  阶段名称
     * @param passed     是否通过
     * @param score      得分
     * @param durationMs 耗时
     */
    public record StageResult(String stageName, boolean passed, double score, long durationMs) {
    }
}
