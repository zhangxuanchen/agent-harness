package io.etclovg.codepilot.multiagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A2A 协议：基于 HintBlock 标签的定向消息路由。对应书中 Ch15 §15.4.4 · 概念示例。
 * <p>Agent 在消息中标记 HintBlock，总线按 target 标签投递到目标 Agent 而非广播，
 * 避免无关 Agent 的上下文被污染。配合 A2A 服务注册实现动态路由。
 */
@Component
public class HintBlockMessageBus {
    private static final Logger log = LoggerFactory.getLogger(HintBlockMessageBus.class);

    private final AgentRegistry registry;          // 服务注册中心（A2A 要素 1）
    private final Map<String, List<AgentRef>> subscribersByRole = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> roundRobin = new ConcurrentHashMap<>();

    public HintBlockMessageBus(AgentRegistry registry) {
        this.registry = registry;
    }

    /** Agent 启动时注册——声明角色和能力（A2A 要素 1 + 2） */
    public void register(AgentRef agent, String role, CapabilityDesc capability) {
        registry.register(agent.agentId(), role, capability);
        subscribersByRole.computeIfAbsent(role, k -> new CopyOnWriteArrayList<>()).add(agent);
    }

    /**
     * 按 HintBlock 标签定向投递（A2A 要素 4）。
     * 调用方在消息中标记 target 角色，总线只投递给该角色的在线实例，
     * 不广播给无关 Agent——保护下游上下文纯净度。
     */
    public void publish(HintMessage message) {
        List<AgentRef> targets = subscribersByRole.getOrDefault(message.targetRole(), List.of());
        // 负载均衡：多个 Reviewer 实例时按 round-robin 选一个
        AgentRef selected = selectByLoad(targets);
        if (selected != null) {
            selected.deliver(message);  // 定向投递，非广播
        } else {
            log.warn("[A2A] 无可用 {} Agent，消息进入待投递队列", message.targetRole());
        }
    }

    // ---- 概念示例桩：round-robin 负载均衡选择目标实例 ----
    private AgentRef selectByLoad(List<AgentRef> targets) {
        if (targets.isEmpty()) return null;
        AtomicInteger idx = roundRobin.computeIfAbsent(
            String.valueOf(System.identityHashCode(targets)), k -> new AtomicInteger(0));
        return targets.get(Math.floorMod(idx.getAndIncrement(), targets.size()));
    }
}
