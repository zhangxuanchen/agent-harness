package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 速率限制感知调度器。
 * <p>对应书中 Ch16 §16.4 —— 评估与执行任务的限流调度。
 * <p>根据上游 API 与模型服务的速率限制，对提交的任务进行排队与节流，
 * 避免触发限流导致整体吞吐下降。
 */
@Component
public class RateLimitAwareScheduler {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAwareScheduler.class);

    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final int maxConcurrent;

    public RateLimitAwareScheduler() {
        this(4);
    }

    public RateLimitAwareScheduler(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    /**
     * 提交任务。
     *
     * @param task 任务
     */
    public void submit(Runnable task) {
        pending.add(task);
        log.debug("提交任务: queued={}, inFlight={}", pending.size(), inFlight.get());
    }

    /**
     * 尝试调度下一个任务。
     *
     * @return 调度成功返回 true
     */
    public boolean scheduleNext() {
        if (inFlight.get() >= maxConcurrent) {
            return false;
        }
        Runnable task = pending.poll();
        if (task == null) {
            return false;
        }
        inFlight.incrementAndGet();
        try {
            task.run();
        } finally {
            inFlight.decrementAndGet();
        }
        return true;
    }

    /**
     * 当前排队任务数。
     *
     * @return 数量
     */
    public int pendingCount() {
        return pending.size();
    }
}
