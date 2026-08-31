package io.etclovg.codepilot.sandbox;

/**
 * 沙箱资源限制记录。
 * <p>对应书中 Ch04 §4.5.4 —— 沙箱资源配额声明。
 * <p>描述单个沙箱实例可使用的 CPU、内存、存储配额、进程数与网络带宽上限，
 * 由 {@link SandboxConfig} 转换而来，作为容器创建时的资源限制参数。
 * <p>部分限制由 cgroups v2 原生支持（cpu/memory/pids），
 * 部分通过 Docker storage driver、系统 ulimit 或 eBPF 等机制实现。
 *
 * @param cpuCores       CPU 核心数上限 → cgroup cpu.max
 * @param memoryMb       内存上限（MB）→ cgroup memory.max
 * @param diskLimitGb    存储配额（GB）→ Docker storage limit
 * @param processLimit   进程数上限 → cgroup pids.max（防 fork 炸弹）
 * @param networkMbps    网络带宽上限（Mbps），0 表示断网 → eBPF 过滤器
 * @param timeoutSeconds 单步执行超时（秒）
 */
public record ResourceLimits(
        double cpuCores,
        int memoryMb,
        int diskLimitGb,
        int processLimit,
        int networkMbps,
        int timeoutSeconds
) {

    /** 默认资源限制（保守配置：CPU 1核 / 512MB / 10GB / 30s 超时 / 断网） */
    public static final ResourceLimits DEFAULT = new ResourceLimits(1.0, 512, 10, 64, 0, 30);

    /** 生产环境常用配置（CPU 2核 / 2GB / 50GB / 60s 超时 / 100Mbps 带宽） */
    public static final ResourceLimits PRODUCTION = new ResourceLimits(2.0, 2048, 50, 128, 100, 60);

    /** 默认资源限制（保守配置） */
    public static ResourceLimits defaults() {
        return DEFAULT;
    }

    /** 构造断网配置 */
    public static ResourceLimits noNetwork(double cpuCores, int memoryMb, int diskLimitGb) {
        return new ResourceLimits(cpuCores, memoryMb, diskLimitGb, 64, 0, 30);
    }

    /** 是否禁用网络 */
    public boolean isNetworkDisabled() {
        return networkMbps <= 0;
    }
}
