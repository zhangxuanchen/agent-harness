package io.etclovg.codepilot.mlops;

/**
 * 成熟度阶段。对应书中 Ch16 §16.6.1。
 * <p>Demo → Pilot → Scale → Evolve 四阶段，{@link #next()} 给出下一阶段（末阶自返）。
 */
public enum Stage {
    DEMO, PILOT, SCALE, EVOLVE;

    /** 下一阶段；EVOLVE 已是末阶，自返。 */
    public Stage next() {
        Stage[] all = values();
        return this == all[all.length - 1] ? this : all[ordinal() + 1];
    }
}
