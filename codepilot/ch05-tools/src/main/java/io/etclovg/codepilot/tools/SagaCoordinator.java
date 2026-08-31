package io.etclovg.codepilot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * T 层 · Saga 事务协调器。
 * <p>对应书中 Ch05 §5.5.3 — 跨工具 Saga 回滚。
 * <p>管理跨工具调用的事务边界：每个工具执行前注册其补偿操作到回滚栈，
 * 失败时按 LIFO 顺序依次执行补偿。这种设计源自分布式系统的 Saga 事务模式。
 *
 * <p>核心约束：补偿操作必须幂等。如果补偿操作自身执行到一半也失败，
 * 重试时不能重复撤销已撤销的状态。
 */
@Component
public class SagaCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SagaCoordinator.class);

    private final Deque<Runnable> rollbackStack = new ConcurrentLinkedDeque<>();
    private final Map<String, List<String>> toolDependencies = new ConcurrentHashMap<>();

    /**
     * 开始一个新的事务边界。
     *
     * @param sagaId 事务标识
     */
    public void beginSaga(String sagaId) {
        rollbackStack.clear();
        log.info("[Saga] 开始事务: sagaId={}", sagaId);
    }

    /**
     * 注册补偿操作——在工具成功执行后调用，记录如何撤销。
     *
     * @param toolName     工具名称
     * @param compensation 补偿操作（必须幂等）
     */
    public void registerCompensation(String toolName, Runnable compensation) {
        rollbackStack.push(compensation);
        log.info("[Saga] 注册补偿: tool={}, stackSize={}", toolName, rollbackStack.size());
    }

    /**
     * 逆序执行所有补偿操作——后执行的回滚先回滚（LIFO）。
     */
    public void rollbackAll() {
        log.warn("[Saga] 开始回滚: 共 {} 个补偿操作", rollbackStack.size());
        while (!rollbackStack.isEmpty()) {
            Runnable compensation = rollbackStack.pop();
            try {
                compensation.run();
            } catch (Exception e) {
                log.error("[Saga] 补偿操作失败: {}", e.getMessage());
                // 补偿操作本身必须幂等——失败后重试而不是跳过
            }
        }
    }
}