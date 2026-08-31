package io.etclovg.codepilot.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 监督 Agent。
 * <p>对应书中 Ch07 §7.5 —— 负责监控和协调子 Agent 的执行。
 */
@Component
public class SupervisorAgent {

    private static final Logger log = LoggerFactory.getLogger(SupervisorAgent.class);

    private final Map<String, SubAgentState> subAgents = new HashMap<>();

    /**
     * 子 Agent 状态。
     */
    public record SubAgentState(
            String agentId,
            String role,
            String status,
            String currentTask
    ) {}

    /**
     * 注册子 Agent。
     */
    public void registerSubAgent(String agentId, String role) {
        subAgents.put(agentId, new SubAgentState(agentId, role, "IDLE", ""));
    }

    /**
     * 分配任务给子 Agent。
     */
    public void assignTask(String agentId, String task) {
        SubAgentState state = subAgents.get(agentId);
        if (state != null) {
            subAgents.put(agentId, new SubAgentState(agentId, state.role(), "WORKING", task));
            log.info("[Supervisor] 分配任务: agent={}, task={}", agentId, task);
        }
    }

    /**
     * 获取所有子 Agent 状态。
     */
    public Map<String, SubAgentState> getSubAgentStates() {
        return Collections.unmodifiableMap(subAgents);
    }
}