package io.etclovg.codepilot.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * MCP Echo Server：通过 STDIO transport 暴露一个 echo 工具。
 * AgentScope MCP Client 通过 McpClientBuilder.stdioTransport() 启动此进程并通信。
 */
public class McpEchoServer {

    public static void main(String[] args) {
        // 1. 创建 STDIO transport provider（需要 McpJsonMapper）
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapper(new ObjectMapper())
        );

        // 2. 定义 echo 工具的 schema
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("message", Map.of("type", "string", "description", "要回显的消息")),
                List.of("message"),
                false,
                null,
                null
        );

        // McpSchema.Tool 构造函数参数顺序：name, title, description, inputSchema, outputSchema, annotations, meta
        McpSchema.Tool echoTool = new McpSchema.Tool(
                "echo",                    // name
                null,                       // title
                "回显输入的消息",            // description
                inputSchema,                // inputSchema
                null,                       // outputSchema
                null,                       // annotations
                null                        // meta
        );

        // 3. 定义工具调用处理逻辑
        McpServerFeatures.SyncToolSpecification echoSpec = new McpServerFeatures.SyncToolSpecification(
                echoTool,
                (exchange, params) -> {
                    String message = (String) params.get("message");
                    return new McpSchema.CallToolResult(
                            List.of(new McpSchema.TextContent("echo: " + message)),
                            false
                    );
                }
        );

        // 4. 构建并启动 server
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("echo-server", "1.0.0")
                .tools(echoSpec)
                .build();

        System.err.println("[McpEchoServer] 启动完成，等待 MCP Client 连接...");

        // 写启动标记文件，供测试验证 server 进程已启动
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/mcp-echo-server.started"),
                    "started at " + java.time.Instant.now());
        } catch (Exception ignored) {}

        // 5. 保持进程运行，直到 transport 关闭（客户端断开连接）
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
