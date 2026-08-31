package io.etclovg.codepilot.multiagent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent Card 三步流程演示：发布 → 发现 → 委派。
 * <p>对应书中 Ch05 §5.2.3——用 ch15-multiagent 的扩展点模拟 A2A 语义。
 * <p>注意：这是进程内消息路由，不是真正的跨进程 A2A 通信。真正的 A2A 需要
 * HTTP 端点暴露 Agent Card + JSON-RPC 消息格式 + OAuth/JWT 认证。
 */
public class AgentCardDemo {

    public static void main(String[] args) {
        // ========== 第 1 步：发布 Agent Card ==========

        // 声明研究 Agent 的能力（对应 Agent Card 的 capabilities 字段）
        CapabilityDesc researchCapability = new CapabilityDesc(
                "{\"type\":\"string\",\"description\":\"研究主题\"}",           // inputSchema
                "{\"type\":\"object\",\"properties\":{\"summary\":\"string\"}}", // outputSchema
                Set.of("web-search", "arxiv-query", "github-read"),            // 工具集
                "响应时间 < 10s, 成功率 > 95%"                                  // SLA
        );

        // 内存注册中心（真正的 A2A 需要HTTP 端点暴露 Agent Card）
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();

        // 消息总线（按角色定向投递任务）
        HintBlockMessageBus bus = new HintBlockMessageBus(registry);

        // 研究Agent 注册到总线，开始接收任务
        AgentRef researchAgent = new AgentRef() {
            @Override
            public String agentId() { return "research-agent"; }

            @Override
            public void deliver(HintMessage message) {
                System.out.println("[research-agent] 收到任务: " + message.payload());
                // 实际项目中这里会触发 Agent 的 ReAct 循环处理任务
            }
        };
        bus.register(researchAgent, "researcher", researchCapability);

        // ========== 第 2 步：发现可用 Agent ==========

        // 按角色查找（对应 Agent Card 的发现流程）
        List<String> researchers = registry.findByRole("researcher");
        System.out.println("[发现] 可用研究 Agent: " + researchers);

        // ========== 第 3 步：委派任务 ==========

        // 发送任务委派（A2A Task 语义：只说"做什么"，不说"用什么工具"）
        HintMessage task = new HintMessage("researcher",
                Map.of("action", "research", "topic", "Agent 工具调用的可靠性"));
        bus.publish(task);
        System.out.println("[委派] 任务已发送: " + task.payload());
    }

    /**
     * AgentRegistry 的内存实现——agentId → (role, capability)。
     * 真正的 A2A 实现需要 HTTP 端点暴露 Agent Card 供其他 Agent 抓取。
     */
    static class InMemoryAgentRegistry implements AgentRegistry {
        private final Map<String, Map.Entry<String, CapabilityDesc>> agents = new ConcurrentHashMap<>();

        @Override
        public void register(String agentId, String role, CapabilityDesc capability) {
            agents.put(agentId, Map.entry(role, capability));
            System.out.println("[注册] Agent Card 已发布: " + agentId + " / role=" + role);
        }

        /**
         * 按角色查找可用 Agent（Agent Card 的发现机制）。
         */
        public List<String> findByRole(String role) {
            return agents.entrySet().stream()
                    .filter(e -> role.equals(e.getValue().getKey()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }
    }
}
