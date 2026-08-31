package io.etclovg.codepilot.orchestration;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Mesh 模式编排器。
 * <p>对应书中 Ch07 §7.3.4 —— 基于 A2A 协议的去中心化 Agent 协作。
 */
@Component
public class MeshOrchestrator {

    private final Map<String, AgentPeer> peers = new ConcurrentHashMap<>();
    private final Map<String, List<A2AMessage>> messageLog = new ConcurrentHashMap<>();

    /**
     * Agent 节点信息。
     */
    public record AgentPeer(String agentId, String role, Set<String> capabilities) {}

    /**
     * A2A 消息。
     */
    public record A2AMessage(String from, String to, String content, String messageType, long timestamp) {}

    /**
     * 注册 Agent 节点。
     */
    public void registerPeer(String agentId, String role, Set<String> capabilities) {
        peers.put(agentId, new AgentPeer(agentId, role, capabilities));
    }

    /**
     * 广播消息给所有连接的 Agent——Mesh 模式的核心通信机制。
     */
    public void broadcast(String from, String content, String messageType) {
        for (String peerId : peers.keySet()) {
            if (!peerId.equals(from)) {
                sendMessage(from, peerId, content, messageType);
            }
        }
    }

    /**
     * 多数投票共识：收集所有 Agent 对 key 问题的判断。
     */
    public Map<String, Long> majorityVote(String question) {
        return peers.keySet().stream()
                .collect(Collectors.groupingBy(
                        peerId -> getResponse(peerId, question),
                        Collectors.counting()));
    }

    /**
     * 发送消息。
     */
    public void sendMessage(String from, String to, String content, String messageType) {
        A2AMessage msg = new A2AMessage(from, to, content, messageType, System.currentTimeMillis());
        messageLog.computeIfAbsent(to, k -> Collections.synchronizedList(new ArrayList<>())).add(msg);
    }

    private String getResponse(String peerId, String question) {
        // 实际实现会调用 Agent 获取回答
        return "AGREE";
    }

    /**
     * 获取节点列表。
     */
    public Set<String> getPeerIds() {
        return Collections.unmodifiableSet(peers.keySet());
    }

    /**
     * 获取消息日志。
     */
    public List<A2AMessage> getMessages(String peerId) {
        return Collections.unmodifiableList(messageLog.getOrDefault(peerId, List.of()));
    }
}