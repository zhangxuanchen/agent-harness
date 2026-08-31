package io.etclovg.codepilot.mlops;

/**
 * 样本量计算器。对应书中 Ch16 §16.3.1。
 *
 * <p>用于 Canary 部署前预估"检测指定幅度退化所需的最小样本量"，
 * 解决"小流量样本量不够、大流量风险敞口太大"的取舍问题。
 *
 * <p>双比例检验的样本量公式（单侧，每组所需样本量）：
 * <pre>
 *   n = ( z_α + z_β )² · 2 · p̄ · (1 − p̄) / δ²
 * </pre>
 * 其中：
 * <ul>
 *   <li>z_α：显著性水平的 z 临界值（α=0.05 → 1.645，单侧）</li>
 *   <li>z_β：统计功效的 z 临界值（β=0.20 即 80% 功效 → 0.842）</li>
 *   <li>p̄：两组比例的均值 p̄ = (p₁ + p₂) / 2（p₁ 基线成功率，p₂ 预期退化后成功率）</li>
 *   <li>δ：可检测的最小退化幅度 δ = p₁ − p₂（如 0.05 表示 5 个百分点）</li>
 * </ul>
 *
 * <p>例：基线 p₁=0.85，预期退化后 p₂=0.80（δ=0.05），α=0.05，功效 80%：
 * <pre>
 *   p̄ = (0.85 + 0.80) / 2 = 0.825
 *   n = (1.645 + 0.842)² · 2 · 0.825 · 0.175 / 0.05²
 *     = 6.185 · 0.28875 / 0.0025 ≈ 714（每组）
 * </pre>
 * 两组共需约 1,428 个样本（书中取约 1,400）。若 Canary 5% 流量、日均 200 请求，
 * Canary 组每日仅 10 个样本，需约 71 天——故小流量场景必须拉长观察期或放大流量。
 *
 * <p>注意：此公式假设两组样本量相等（n₁=n₂=n）。若流量比例不对称
 * （如 Canary 5% vs Stable 95%），需用不平衡样本量公式校正。
 */
public final class SampleSizeCalculator {

    private SampleSizeCalculator() {
    }

    /**
     * 计算每组所需的最小样本量（双比例检验，单侧）。
     *
     * @param baselineRate    基线成功率 p₁（0-1）
     * @param degradedRate    预期退化后成功率 p₂（0-1，p₂ &lt; p₁）
     * @param zAlpha          显著性水平 z 临界值（α=0.05 → 1.645）
     * @param zBeta           统计功效 z 临界值（80% 功效 → 0.842）
     * @return 每组所需最小样本量（向上取整）
     */
    public static int samplesPerGroup(double baselineRate, double degradedRate,
                                      double zAlpha, double zBeta) {
        if (baselineRate <= 0 || baselineRate >= 1) {
            throw new IllegalArgumentException("基线成功率必须在 (0,1) 区间");
        }
        if (degradedRate <= 0 || degradedRate >= 1) {
            throw new IllegalArgumentException("退化后成功率必须在 (0,1) 区间");
        }
        double delta = baselineRate - degradedRate;
        if (delta <= 0) {
            throw new IllegalArgumentException("退化后成功率必须低于基线");
        }
        double pBar = (baselineRate + degradedRate) / 2; // p̄ = (p₁+p₂)/2
        double numerator = Math.pow(zAlpha + zBeta, 2) * 2 * pBar * (1 - pBar);
        double n = numerator / (delta * delta);
        return (int) Math.ceil(n);
    }

    /**
     * 估算 Canary 组累积所需样本量所需的天数。
     *
     * @param samplesPerGroup 每组所需样本量
     * @param dailyRequests   每日总请求量
     * @param trafficPercent  Canary 流量占比（0-1）
     * @return 所需天数（向上取整）
     */
    public static int daysToAccumulate(int samplesPerGroup, int dailyRequests, double trafficPercent) {
        if (dailyRequests <= 0 || trafficPercent <= 0) {
            throw new IllegalArgumentException("每日请求量和流量占比必须为正");
        }
        // Canary 组每日可累积的样本量
        int canaryDaily = (int) (dailyRequests * trafficPercent);
        return (int) Math.ceil((double) samplesPerGroup / canaryDaily);
    }
}
