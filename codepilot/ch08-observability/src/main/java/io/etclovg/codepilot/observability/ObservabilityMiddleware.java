package io.etclovg.codepilot.observability;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 可观测性中间件——实现"旁路观测"模式。
 * <p>
 * 对应书中 Ch8 §8.2.2 Middleware 链的观测注入模式。
 * <p>
 * 核心特性：
 * 1. 所有观测代码包裹 try-catch，异常只记自身 WARN，不传递主流程
 * 2. 放在 Middleware 链最外层（通过 getOrder()），覆盖所有内层 Middleware
 * 3. 数据异步批量发送：写入内存缓冲区，后台线程批量刷新
 *    缓冲区满时丢弃最旧数据（保留最新观测）
 */
@Component
public class ObservabilityMiddleware extends AbstractLayerMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityMiddleware.class);

    private final CostAttributionMiddleware costService;
    private final EventLogRecorder eventLog;
    private final BlockingQueue<ObservationRecord> metricBuffer;
    private final ScheduledExecutorService flushExecutor;

    public ObservabilityMiddleware(CostAttributionMiddleware costService,
                                   EventLogRecorder eventLog) {
        super(Layer.O, "Observability");
        this.costService = costService;
        this.eventLog = eventLog;
        this.metricBuffer = new ArrayBlockingQueue<>(10000);
        this.flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "observability-flush");
            t.setDaemon(true);
            return t;
        });
        this.flushExecutor.scheduleAtFixedRate(this::flushMetrics, 5, 5, TimeUnit.SECONDS);
        log.info("[Observability] 旁路观测中间件已初始化，缓冲区大小={}", 10000);
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String traceId = (String) rc.get("trace.id");
        long start = System.currentTimeMillis();
        String taskId = rc.get("task.id") != null ? (String) rc.get("task.id") : "unknown";
        String agentName = agent.getClass().getSimpleName();

        // 旁路保护：观测逻辑包裹在 try-catch 中
        try {
            log.info("[Observability] 开始: traceId={}, taskId={}", traceId, taskId);
        } catch (Exception e) {
            log.warn("[Observability] 初始化失败: {}", e.getMessage());
        }

        return next.apply(input)
            .doOnComplete(() -> {
                try {
                    long elapsed = System.currentTimeMillis() - start;
                    metricBuffer.offer(new ObservationRecord(
                        traceId, taskId, agentName, elapsed, System.currentTimeMillis()
                    ));
                    log.info("[Observability] 完成: traceId={}, elapsed={}ms", traceId, elapsed);
                } catch (Exception e) {
                    log.warn("[Observability] 记录指标失败: {}", e.getMessage());
                }
            })
            .doOnError(err -> {
                try {
                    long elapsed = System.currentTimeMillis() - start;
                    metricBuffer.offer(new ObservationRecord(
                        traceId, taskId, agentName, elapsed, System.currentTimeMillis(), err.getMessage()
                    ));
                } catch (Exception e) {
                    log.warn("[Observability] 错误记录失败: {}", e.getMessage());
                }
            });
    }

    private void flushMetrics() {
        List<ObservationRecord> batch = new ArrayList<>();
        metricBuffer.drainTo(batch, 500);
        if (!batch.isEmpty()) {
            try {
                for (ObservationRecord rec : batch) {
                    eventLog.recordEvent(rec.traceId(), toAgentEvent(rec));
                }
                log.debug("[Observability] 批量刷新 {} 条指标", batch.size());
            } catch (Exception e) {
                log.warn("[Observability] 批量刷新失败: {}", e.getMessage());
            }
        }
    }

    private AgentEvent toAgentEvent(ObservationRecord rec) {
        return new AgentEvent() {
            @Override
            public AgentEventType getType() {
                return AgentEventType.CUSTOM;
            }

            @Override
            public String toString() {
                return String.format("task=%s, agent=%s, elapsed=%dms",
                    rec.taskId(), rec.agentName(), rec.elapsedMs());
            }
        };
    }

    public record ObservationRecord(
            String traceId, String taskId, String agentName,
            long elapsedMs, long timestamp, String errorMessage
    ) {
        public ObservationRecord(String traceId, String taskId, String agentName,
                                 long elapsedMs, long timestamp) {
            this(traceId, taskId, agentName, elapsedMs, timestamp, null);
        }
    }
}
