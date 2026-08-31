package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * E2B 云沙箱客户端——教学示意实现。
 * <p>对应书中 Ch04 §4.2.4。AgentScope 官方实现通过 E2B Java SDK（或直接 REST API）
 * 调用 e2b.dev 云端创建、执行、销毁 Firecracker microVM。
 * <p>使用前**必须先做三项额外工作**（详见 {@link E2BSandboxClientOptions} 类注释）：
 * 1) e2b.dev 注册 + 获取 API Key；2) 创建 Sandbox Template 预安装工具链；
 * 3) 设置月度预算告警 + 用量阈值提醒。
 * <p>本类为日志桩，每一步打印等价的 E2B API 调用，并估算本次沙箱的费用（~$0.002/分钟），
 * 方便读者理解成本构成。接入 E2B SDK 时只替换方法内部实现，Agent 代码零改动。
 */
@Component
public class E2BSandboxClient {

    private static final Logger log = LoggerFactory.getLogger(E2BSandboxClient.class);

    /** 费用估算常量（教学示意值，实际以 e2b.dev 报价页为准）：~$0.002/分钟 = $0.12/小时 */
    private static final double COST_USD_PER_MINUTE = 0.002;

    public record E2BExecResult(
            boolean success,
            String output,
            int exitCode,
            long durationMs,
            String sandboxId,
            double estimatedCostUsd
    ) {}

    /**
     * 创建一个 E2B 沙箱（Firecracker microVM）。
     * <p>对应 E2B SDK：{@code Sandbox.create(templateId, apiKey, { timeoutMs, cwd })}.
     *
     * @return 沙箱 ID（后续 exec / destroy 都需要）
     */
    public String createSandbox(E2BSandboxClientOptions options) {
        if (options.getApiKey() == null || options.getApiKey().isBlank()) {
            throw new IllegalStateException(
                "E2B API Key 为空，请先在 e2b.dev 注册并获取 Key，" +
                "再通过环境变量 E2B_API_KEY 或 codepilot.sandbox.e2b.api-key 注入。" +
                "（§4.2.4 使用前需完成 3 项额外工作：注册 Key/创建 Template/设预算告警）");
        }
        String id = "e2b-" + UUID.randomUUID().toString().substring(0, 10);
        log.info("[E2B] 创建沙箱: id={}, region={}, template={}, cpu={}c, memory={}MB, 联网={}",
                id, options.getRegion(),
                options.getTemplateId() == null ? "(default)" : options.getTemplateId(),
                options.getCpuCores(), options.getMemoryMb(),
                options.isAllowInternetAccess() ? "ON" : "OFF");
        log.info("[E2B]   生命周期安全网: maxLifetime={}s, destroyOnIdle={}, idleTimeout={}s",
                options.getMaxSandboxLifetimeSeconds(),
                options.isDestroyOnIdle(), options.getIdleTimeoutSeconds());
        return id;
    }

    /**
     * 在已创建的沙箱内执行命令。
     */
    public E2BExecResult exec(String sandboxId, E2BSandboxClientOptions options, String command) {
        long start = System.currentTimeMillis();
        log.info("[E2B] 执行命令: sandbox={}, cmd={}", sandboxId, shortCmd(command));
        long durationMs = System.currentTimeMillis() - start;
        double minutes = durationMs / 60_000.0;
        double costUsd = Math.max(0.0001, minutes * COST_USD_PER_MINUTE);
        log.info("[E2B] 执行完成: 耗时={}ms, 本次估算费用=${}", durationMs, String.format("%.5f", costUsd));
        return new E2BExecResult(true, "", 0, durationMs, sandboxId, costUsd);
    }

    /**
     * 立即销毁沙箱（结束计费）。Agent 任务结束后必须调用，否则按 maxLifetime 才会强制停止。
     */
    public void destroySandbox(String sandboxId) {
        log.info("[E2B] 销毁沙箱（结束计费）: {}", sandboxId);
    }

    /** 读取沙箱内文件（Agent 拿代码输出结果用） */
    public byte[] readFile(String sandboxId, String path) {
        log.info("[E2B] 读取沙箱文件: sandbox={}, path={}", sandboxId, path);
        return new byte[0];
    }

    /** 上传文件到沙箱内（Agent 把用户代码/数据集传进去） */
    public void writeFile(String sandboxId, String path, byte[] content) {
        log.info("[E2B] 上传文件到沙箱: sandbox={}, path={}, size={}B", sandboxId, path, content.length);
    }

    private String shortCmd(String cmd) {
        return cmd.length() <= 80 ? cmd : cmd.substring(0, 80) + "...";
    }
}
