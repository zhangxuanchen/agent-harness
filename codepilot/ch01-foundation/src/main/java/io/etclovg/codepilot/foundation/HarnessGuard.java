package io.etclovg.codepilot.foundation;

import io.etclovg.codepilot.core.Layer;

import java.util.Map;

/**
 * Harness 守护接口。
 * <p>对应书中 Ch01 §1.4 —— Harness 的通用守护/验证接口。
 * <p>各层 Advisor 通过实现此接口提供特定的守护逻辑。
 */
public interface HarnessGuard {

    /**
     * 获取守护名称。
     */
    String getName();

    /**
     * 获取所属层。
     */
    Layer getLayer();

    /**
     * 执行守护检查。
     *
     * @param context 上下文信息
     * @return 检查结果
     */
    GuardResult check(Map<String, Object> context);

    /**
     * 守护检查结果。
     */
    record GuardResult(
            boolean passed,
            String guardName,
            Layer layer,
            String message,
            Map<String, Object> details
    ) {
        public static GuardResult pass(String guardName, Layer layer) {
            return new GuardResult(true, guardName, layer, "OK", Map.of());
        }

        public static GuardResult fail(String guardName, Layer layer, String message) {
            return new GuardResult(false, guardName, layer, message, Map.of());
        }
    }
}