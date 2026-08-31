package io.etclovg.codepilot.sandbox;

/**
 * 沙箱隔离级别枚举。
 * <p>对应书中 Ch04 §4.2 & §4.7.2 —— 沙箱执行环境的隔离级别选择。
 * <p>不同级别在启动速度、隔离强度与资源开销上各有取舍，
 * 由 {@link SandboxProfile} 与部署环境共同决定。
 * <ul>
 *   <li>{@link #DOCKER}     Docker 容器，通用且隔离良好</li>
 *   <li>{@link #FIRECRACKER} Firecracker microVM，强隔离轻量</li>
 *   <li>{@link #GVisOR}     gVisor 沙箱，系统调用级隔离</li>
 *   <li>{@link #WASM}       WebAssembly 运行时，极速启动</li>
 *   <li>{@link #PROCESS}    进程级隔离，最轻量但隔离最弱</li>
 * </ul>
 */
public enum SandboxIsolation {

    /** Docker 容器：通用且隔离良好（冷启动 ~2.1s） */
    DOCKER("Docker", true, 2100),
    /** Firecracker microVM：强隔离轻量（P99 冷启动 125ms） */
    FIRECRACKER("Firecracker", true, 125),
    /** gVisor 沙箱：系统调用级隔离（P99 冷启动 452ms，典型 50ms） */
    GVisOR("gVisor", true, 452),
    /** WebAssembly 运行时：极速启动（~10ms） */
    WASM("WASM", false, 10),
    /** 进程级隔离：最轻量但隔离最弱（~5ms） */
    PROCESS("Process", false, 5);

    private final String displayName;
    private final boolean strongIsolation;
    private final int startupMs;

    SandboxIsolation(String displayName, boolean strongIsolation, int startupMs) {
        this.displayName = displayName;
        this.strongIsolation = strongIsolation;
        this.startupMs = startupMs;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 是否提供强隔离（独立内核或系统调用拦截）。
     *
     * @return 强隔离后端返回 true
     */
    public boolean isStrongIsolation() {
        return strongIsolation;
    }

    /**
     * 冷启动耗时（毫秒）。
     *
     * @return 启动时间
     */
    public int getStartupMs() {
        return startupMs;
    }
}
