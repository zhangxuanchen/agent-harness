package io.etclovg.codepilot.tools;

import io.agentscope.core.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 客户服务工具集。
 * <p>对应书中 Ch05 §5.3 —— 客户服务场景的常用工具集合。
 */
@Component
public class CustomerServiceTools {

    private final Map<String, Map<String, String>> customerDatabase = new HashMap<>();
    private final Map<String, List<String>> ticketDatabase = new HashMap<>();

    /**
     * 查询客户信息。
     */
    @Tool(description = "根据客户ID查询客户信息")
    public String getCustomerInfo(String customerId) {
        Map<String, String> info = customerDatabase.get(customerId);
        return info != null ? info.toString() : "客户 " + customerId + " 未找到";
    }

    /**
     * 创建工单。
     */
    @Tool(description = "为客户创建新的服务工单")
    public String createTicket(String customerId, String description) {
        String ticketId = "TKT-" + UUID.randomUUID().toString().substring(0, 8);
        ticketDatabase.computeIfAbsent(customerId, k -> new ArrayList<>())
                .add(ticketId + ": " + description);
        return "工单已创建: " + ticketId;
    }

    /**
     * 查询工单状态。
     */
    @Tool(description = "查询客户的所有工单")
    public String getTicketStatus(String customerId) {
        List<String> tickets = ticketDatabase.getOrDefault(customerId, List.of());
        return tickets.isEmpty() ? "客户 " + customerId + " 没有工单" : String.join("; ", tickets);
    }
}