package io.etclovg.codepilot.data;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 知识保鲜守护。
 * <p>对应书中 Ch13 §13.1 —— 知识保鲜：时效性与版本管理。
 * <p>实现时效评分（指数衰减，按知识类型差异化 λ）+ 版本绑定
 * （{@code docId@commitSha} 复合版本号）+ 过期标记（&gt;180 天）。
 * 满足 EU AI Act 2026-08 数据血缘可追溯要求。
 *
 * <p><b>概念示例</b>：方法签名对齐章节调用，实现为桩。
 */
@Component
public class KnowledgeFreshnessGuard {

    /** 按知识类型差异化的衰减系数（半衰期 = ln2/λ） */
    private static final Map<String, Double> LAMBDA_BY_TYPE = Map.of(
        "api_doc", 0.01,      // 半衰期 ~70 天
        "regulation", 0.001,  // 半衰期 ~700 天
        "case_history", 0.0   // 不衰减
    );
    private static final int STALE_THRESHOLD_DAYS = 180;

    /**
     * 对知识命中计算时效评分与版本指纹。
     *
     * @param hit 知识命中
     * @param now 当前时间
     * @return 评分后的命中（含最终分数、是否过期、版本指纹）
     */
    public ScoredHit score(KnowledgeHit hit, Instant now) {
        double lambda = LAMBDA_BY_TYPE.getOrDefault(hit.type(), 0.005);
        long ageDays = Duration.between(hit.publishedAt(), now).toDays();
        double timeWeight = Math.exp(-lambda * ageDays);  // 指数衰减
        double finalScore = hit.baseScore() * timeWeight;
        boolean stale = ageDays > STALE_THRESHOLD_DAYS;
        return new ScoredHit(hit, finalScore, stale, hit.docId() + "@" + hit.commitSha());
    }

    /**
     * 知识命中记录。
     *
     * @param docId      文档 ID
     * @param commitSha  Git commit 指纹
     * @param type       知识类型（api_doc / regulation / case_history）
     * @param baseScore  基础相关性分数
     * @param publishedAt 发布时间
     */
    public record KnowledgeHit(String docId, String commitSha, String type,
                               double baseScore, Instant publishedAt) {
    }

    /**
     * 评分后的命中。
     *
     * @param hit               原始命中
     * @param finalScore        时效加权后最终分数
     * @param stale             是否过期
     * @param versionFingerprint 版本指纹（docId@commitSha）
     */
    public record ScoredHit(KnowledgeHit hit, double finalScore, boolean stale,
                            String versionFingerprint) {
    }
}
