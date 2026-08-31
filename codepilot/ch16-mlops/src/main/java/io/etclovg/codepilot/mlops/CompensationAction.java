package io.etclovg.codepilot.mlops;

import java.util.Map;

/**
 * 补偿操作。对应书中 Ch16 §16.3.2。
 * <p>据正向操作的参数与执行结果执行撤销。实现必须幂等——重复执行不产生额外损害
 * （如对已关闭工单再次 close 应为 no-op）。
 */
@FunctionalInterface
public interface CompensationAction {
    void compensate(Map<String, Object> params, Object result);
}
