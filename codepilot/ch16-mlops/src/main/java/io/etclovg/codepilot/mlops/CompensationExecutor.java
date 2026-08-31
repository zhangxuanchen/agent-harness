package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;

/**
 * 补偿执行器。对应书中 Ch16 §16.3.2。
 * <p>回滚时从补偿日志取部署后所有副作用操作，按时间逆序执行对应补偿操作。
 * 补偿操作须幂等——重试不产生额外损害。
 */
@Service
public class CompensationExecutor {

    private static final Logger log = LoggerFactory.getLogger(CompensationExecutor.class);

    private final CompensationRegistry registry;
    private final CompensationLog compensationLog;

    public CompensationExecutor(CompensationRegistry registry, CompensationLog compensationLog) {
        this.registry = registry;
        this.compensationLog = compensationLog;
    }

    /**
     * 逆序执行补偿：取部署后所有副作用操作，按时间倒序补偿。
     *
     * @param deploymentTime 部署时间点
     */
    public void rollback(Instant deploymentTime) {
        compensationLog.getSince(deploymentTime).stream()
                .sorted(Comparator.comparing(CompensableOp::timestamp).reversed()) // 逆序
                .forEach(op -> {
                    CompensationAction action = registry.get(op.toolName());
                    log.info("补偿执行: tool={}, op={}", op.toolName(), op.opId());
                    action.compensate(op.params(), op.result()); // 幂等：重复执行不产生额外损害
                });
    }
}
