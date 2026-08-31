package io.etclovg.codepilot.mlops;

/**
 * 双比例 z 检验（Two-proportion z-test）。对应书中 Ch16 §16.3.1。
 *
 * <p>用于 Canary 部署中检测候选版本相对稳定版本的成功率退化是否统计显著。
 * Agent 的关键指标（成功率、幻觉率）是比例数据（proportion），
 * 比连续数据（如延迟）需要更大的样本量才能建立统计置信度。
 *
 * <p>检验统计量：
 * <pre>
 *   z = (p̂₁ − p̂₂) / √( p̂·(1−p̂)·(1/n₁ + 1/n₂) )
 * </pre>
 * 其中 p̂₁、p̂₂ 为两组样本比例，n₁、n₂ 为样本量，
 * p̂ = (x₁ + x₂) / (n₁ + n₂) 为合并比例（pooled proportion），
 * x₁、x₂ 为两组的成功次数。
 *
 * <p>判定规则（单侧检验，检测退化即 p̂₂ &lt; p̂₁）：
 * <ul>
 *   <li>z &lt; −z_α（如 α=0.05 时 z_α=1.645）→ 退化统计显著，触发回滚</li>
 *   <li>z ≥ −z_α → 无法拒绝原假设，差异可能在自然波动范围内</li>
 * </ul>
 *
 * <p>注意：z 检验要求 n·p̂ ≥ 5 且 n·(1−p̂) ≥ 5（正态近似成立条件）。
 * 样本量不足时应改用 Fisher 精确检验（此处未实现）。
 */
public final class ProportionZTest {

    /** 常用显著性水平对应的 z 临界值（单侧）。 */
    public static final double Z_ALPHA_05 = 1.645; // α=0.05
    public static final double Z_ALPHA_01 = 2.326; // α=0.01

    private ProportionZTest() {
    }

    /**
     * 计算双比例 z 检验的 z 统计量。
     *
     * @param successBaseline 基线版本成功次数（x₁）
     * @param totalBaseline   基线版本样本总量（n₁）
     * @param successCandidate 候选版本成功次数（x₂）
     * @param totalCandidate   候选版本样本总量（n₂）
     * @return z 统计量（负值表示候选版本退化）
     */
    public static double zStatistic(int successBaseline, int totalBaseline,
                                    int successCandidate, int totalCandidate) {
        if (totalBaseline <= 0 || totalCandidate <= 0) {
            throw new IllegalArgumentException("样本量必须为正");
        }
        double p1 = (double) successBaseline / totalBaseline;
        double p2 = (double) successCandidate / totalCandidate;
        // 合并比例（pooled proportion）
        double pooled = (double) (successBaseline + successCandidate)
                / (totalBaseline + totalCandidate);
        double se = Math.sqrt(pooled * (1 - pooled)
                * (1.0 / totalBaseline + 1.0 / totalCandidate));
        if (se == 0) {
            return 0.0;
        }
        return (p2 - p1) / se; // 负值 = 候选版本退化
    }

    /**
     * 判定候选版本相对基线版本是否发生统计显著的退化。
     *
     * @param successBaseline 基线版本成功次数
     * @param totalBaseline   基线版本样本总量
     * @param successCandidate 候选版本成功次数
     * @param totalCandidate   候选版本样本总量
     * @param zAlpha          单侧显著性水平对应的 z 临界值（如 1.645 对应 α=0.05）
     * @return 退化统计显著返回 true
     */
    public static boolean isSignificantDegradation(int successBaseline, int totalBaseline,
                                                   int successCandidate, int totalCandidate,
                                                   double zAlpha) {
        double z = zStatistic(successBaseline, totalBaseline,
                successCandidate, totalCandidate);
        return z < -zAlpha; // 候选版本成功率显著低于基线
    }

    // ==================== Welch's t 检验（连续型评估分数，书中 §16.3.1） ====================

    /**
     * 单侧 α 对应的 t 临界值近似（Welch–Satterthwaite 自由度查表）。
     * <p>大自由度收敛于正态；生产实现应查完整 t 分布表或引入 Apache Commons Math。
     *
     * @param df    自由度
     * @param alpha 显著性水平（仅用于语义，当前固定返回 α=0.05 的近似）
     * @return 单侧 t 临界值
     */
    public static double tCriticalOneSided(double df, double alpha) {
        // 大自由度收敛于正态：df→∞ 时 t_0.05,df → 1.645；df=30 时约 1.697；df=10 时约 1.812
        if (df > 200) return 1.645;
        if (df > 60) return 1.671;
        if (df > 30) return 1.697;
        if (df > 10) return 1.812;
        return 2.228; // df=10 的保守近似
    }

    /**
     * 计算 Welch's t 统计量（负值表示候选版本分数下降）。
     * <p>对应书中 §16.3.1 —— 连续型评估分数（llm-as-judge 打分 / 语义相似度 / BLEU 等）
     * 的退化检测。不假设两组方差相等，自由度采用 Welch–Satterthwaite 近似。
     *
     * @param baselineScores  基线版本连续分数样本
     * @param candidateScores 候选版本连续分数样本
     * @return t 统计量（负值 = 候选版本分数下降）
     */
    public static double welchTTest(double[] baselineScores, double[] candidateScores) {
        if (baselineScores == null || candidateScores == null
                || baselineScores.length < 2 || candidateScores.length < 2) {
            throw new IllegalArgumentException("每组至少需要 2 个样本");
        }
        double m1 = mean(baselineScores), m2 = mean(candidateScores);
        double v1 = variance(baselineScores, m1), v2 = variance(candidateScores, m2);
        int n1 = baselineScores.length, n2 = candidateScores.length;
        double se = Math.sqrt(v1 / n1 + v2 / n2);
        if (se == 0) return 0.0;
        return (m2 - m1) / se; // 负值 = 候选版本分数下降
    }

    /**
     * 判定候选版本连续分数是否统计显著下降（单侧 Welch's t 检验）。
     *
     * @param baselineScores  基线版本连续分数样本
     * @param candidateScores 候选版本连续分数样本
     * @param alpha           显著性水平（如 0.05）
     * @return 分数统计显著下降返回 true
     */
    public static boolean welchSignificantDegradation(double[] baselineScores, double[] candidateScores,
                                                      double alpha) {
        double t = welchTTest(baselineScores, candidateScores);
        double v1 = variance(baselineScores, mean(baselineScores));
        double v2 = variance(candidateScores, mean(candidateScores));
        int n1 = baselineScores.length, n2 = candidateScores.length;
        double df = Math.pow(v1 / n1 + v2 / n2, 2)
                / (Math.pow(v1 / n1, 2) / (n1 - 1) + Math.pow(v2 / n2, 2) / (n2 - 1));
        return t < -tCriticalOneSided(df, alpha);
    }

    private static double mean(double[] xs) {
        double s = 0;
        for (double x : xs) s += x;
        return s / xs.length;
    }

    private static double variance(double[] xs, double mean) {
        double s = 0;
        for (double x : xs) s += (x - mean) * (x - mean);
        return s / (xs.length - 1);
    }
}
