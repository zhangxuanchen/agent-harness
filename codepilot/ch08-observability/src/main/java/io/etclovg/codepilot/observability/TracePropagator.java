package io.etclovg.codepilot.observability;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 跨调用 traceId 传播器——确保 trace 链在跨 Agent 调用和跨线程场景下不断裂。
 * <p>
 * 对应书中 Ch8 §8.2.2 跨调用 traceId 传播。
 */
@Component
public class TracePropagator {

    /**
     * Agent 内部传递：将当前 traceId 注入子 Agent 的上下文 Map。
     * 子 Agent 在启动时从上下文 Map 中读取 traceId 并注入 RuntimeContext。
     */
    public Map<String, Object> prepareSubAgentContext(RuntimeContext parentRc, Agent subAgent) {
        String traceId = (String) parentRc.get("trace.id");
        Map<String, Object> subContext = new HashMap<>();
        if (traceId != null) {
            subContext.put("trace.id", traceId);
            subContext.put("trace.parent", subAgent.getClass().getSimpleName());
        }
        return subContext;
    }

    /**
     * 将上下文 Map 中的 trace 信息注入 RuntimeContext。
     * 子 Agent 在 onAgent 方法开始时调用此方法。
     */
    public void injectTraceFromMap(RuntimeContext rc, Map<String, Object> contextMap) {
        if (contextMap.containsKey("trace.id")) {
            String traceId = (String) contextMap.get("trace.id");
            rc.put("trace.id", traceId);
            MDC.put("trace.id", traceId);
        }
        if (contextMap.containsKey("trace.parent")) {
            rc.put("trace.parent", contextMap.get("trace.parent"));
        }
    }

    /**
     * 生成 W3C Trace Context 标准的 traceparent header 值。
     * 外部 HTTP 调用时使用此值注入 header。
     */
    public String generateTraceParent(String traceId) {
        String spanId = UUID.randomUUID().toString().substring(0, 16);
        return String.format("00-%s-%s-01", traceId, spanId);
    }

    /**
     * 异步回调：确保回调线程能恢复 traceId 上下文。
     */
    public Runnable wrapWithContext(String traceId, Runnable task) {
        return () -> {
            String originalTraceId = MDC.get("trace.id");
            try {
                MDC.put("trace.id", traceId);
                task.run();
            } finally {
                if (originalTraceId != null) {
                    MDC.put("trace.id", originalTraceId);
                } else {
                    MDC.remove("trace.id");
                }
            }
        };
    }
}
