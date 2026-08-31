package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 评估工作线程。
 * <p>对应书中 Ch16 §16.3 —— 评估任务的执行单元。
 * <p>从评估队列领取任务，在沙箱中执行被测 Agent 并收集结果，
 * 是 {@link EvalWorkerPool} 中实际执行的工作线程抽象。
 */
@Component
public class EvalWorker {

    private static final Logger log = LoggerFactory.getLogger(EvalWorker.class);

    private final String workerId;
    private volatile boolean busy;

    public EvalWorker() {
        this("worker-" + Thread.currentThread().threadId());
    }

    public EvalWorker(String workerId) {
        this.workerId = workerId;
    }

    /**
     * 执行一个评估任务。
     *
     * @param taskId 任务 ID
     * @return 执行是否成功
     */
    public boolean execute(String taskId) {
        busy = true;
        try {
            log.info("Worker 执行任务: worker={}, task={}", workerId, taskId);
            return true;
        } finally {
            busy = false;
        }
    }

    public String getWorkerId() {
        return workerId;
    }

    public boolean isBusy() {
        return busy;
    }
}
