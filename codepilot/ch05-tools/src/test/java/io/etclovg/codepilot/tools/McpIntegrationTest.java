package io.etclovg.codepilot.tools;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP Server + Client 连通性测试。
 *
 * 测试流程：
 * 1. McpClientBuilder 通过 STDIO transport 启动 McpEchoServer 子进程
 * 2. Client 发现 Server 暴露的 echo 工具
 * 3. Client 调用 echo 工具并验证返回值
 * 4. 将 MCP 工具注册到 AgentScope Toolkit，验证 AgentScope 集成
 */
class McpIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(McpIntegrationTest.class);

    private McpClientWrapper mcpClient;

    @BeforeEach
    void setUp() throws Exception {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + "/bin/java";

        // Maven surefire 启动测试 JVM 时 java.class.path 不完整（仅 surefire boot jar），
        // 启动 Server 子进程需要完整 classpath：
        //   main classes + test classes + 依赖 jar（由 maven-dependency-plugin 输出到文件）
        String testClasses = System.getProperty("test.classes.dir", "target/test-classes");
        String mainClasses = System.getProperty("main.classes.dir", "target/classes");
        String depClasspathFile = System.getProperty("dep.classpath.file", "target/test-classpath.txt");
        String depClasspath = java.nio.file.Files.readString(java.nio.file.Path.of(depClasspathFile)).trim();
        String classpath = mainClasses + ":" + testClasses + ":" + depClasspath;

        log.info("[Test] 启动 MCP Client，连接 Echo Server");
        log.info("[Test] java: {}", javaBin);
        log.info("[Test] classpath 长度: {}", classpath.length());
        log.info("[Test] classpath 前 300 字符: {}", classpath.substring(0, Math.min(300, classpath.length())));

        mcpClient = McpClientBuilder.create("echo-server")
                .stdioTransport(javaBin, "-cp", classpath,
                        "-Dlogback.configurationFile=logback-echo-server.xml",
                        "io.etclovg.codepilot.tools.McpEchoServer")
                .initializationTimeout(Duration.ofSeconds(30))
                .timeout(Duration.ofSeconds(30))
                .buildSync();

        // buildSync() 只创建 wrapper，需要显式调用 initialize() 建立连接
        mcpClient.initialize().block();
        log.info("[Test] MCP Client 初始化完成");
    }

    @AfterEach
    void tearDown() {
        if (mcpClient != null) {
            mcpClient.close();
        }
    }

    @Test
    void testListTools() {
        log.info("[Test] ========== 测试工具发现 ==========");

        List<McpSchema.Tool> tools = mcpClient.listTools().block();

        assertNotNull(tools, "工具列表不应为 null");
        assertFalse(tools.isEmpty(), "工具列表不应为空");

        McpSchema.Tool echoTool = tools.stream()
                .filter(t -> "echo".equals(t.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 echo 工具"));

        assertEquals("echo", echoTool.name());
        assertNotNull(echoTool.description());
        assertNotNull(echoTool.inputSchema());
        assertTrue(echoTool.inputSchema().required().contains("message"));

        log.info("[Test] 发现工具: name={}, description={}", echoTool.name(), echoTool.description());
        log.info("[Test] 工具发现测试通过");
    }

    @Test
    void testCallTool() {
        log.info("[Test] ========== 测试工具调用 ==========");

        McpSchema.CallToolResult result = mcpClient.callTool("echo", Map.of("message", "hello mcp"))
                .block();

        assertNotNull(result, "调用结果不应为 null");
        assertFalse(result.isError(), "调用不应返回错误");
        assertNotNull(result.content(), "返回内容不应为 null");
        assertEquals(1, result.content().size(), "应返回 1 条内容");

        McpSchema.Content content = result.content().get(0);
        assertInstanceOf(McpSchema.TextContent.class, content, "内容应为 TextContent");

        String text = ((McpSchema.TextContent) content).text();
        assertEquals("echo: hello mcp", text, "回显内容应匹配");

        log.info("[Test] 调用结果: {}", text);
        log.info("[Test] 工具调用测试通过");
    }

    @Test
    void testToolkitIntegration() {
        log.info("[Test] ========== 测试 AgentScope Toolkit 集成 ==========");

        Toolkit toolkit = new Toolkit();
        toolkit.registerMcpClient(mcpClient).block();

        var toolNames = toolkit.getToolNames();
        assertNotNull(toolNames);
        assertFalse(toolNames.isEmpty(), "Toolkit 应包含 MCP 工具");
        assertTrue(toolNames.contains("echo"), "Toolkit 应包含 echo 工具");

        var echoTool = toolkit.getTool("echo");
        assertNotNull(echoTool, "通过名称应能获取到 echo 工具");

        log.info("[Test] Toolkit 注册成功: toolNames={}", toolNames);
        log.info("[Test] Toolkit 集成测试通过");
    }
}
