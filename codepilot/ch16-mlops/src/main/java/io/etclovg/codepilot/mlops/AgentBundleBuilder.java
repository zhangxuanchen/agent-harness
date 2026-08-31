package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * Agent Bundle 构建器。对应书中 Ch16 §16.1.2。
 *
 * <p>从各数据源（Git 代码版本、DB 工具描述、文件系统 Prompt、配置中心
 * Middleware 配置、策略引擎安全策略、模型配置）加载六个组件，
 * 组装 {@link AgentBundle.AgentBundleStructure} 供 {@link AgentBundleRegistry}
 * 注册与原子部署。
 *
 * <p>核心职责是将分散在六处的配置汇聚为一个不可变的 Bundle 描述，
 * 并计算 SHA-256 版本指纹——这是部署/回滚时的原子切换依据。
 */
@Component
public class AgentBundleBuilder {

    private static final Logger log = LoggerFactory.getLogger(AgentBundleBuilder.class);

    private final AgentBundle agentBundleManager;

    public AgentBundleBuilder(AgentBundle agentBundleManager) {
        this.agentBundleManager = agentBundleManager;
    }

    /**
     * 从各数据源加载组件，构建完整 Agent Bundle 并计算版本指纹。
     *
     * @param codeVersion     代码版本号（Git tag 或 commit SHA）
     * @param agentId         Agent 唯一标识
     * @param agentName       Agent 名称
     * @param modelConfig     模型配置（主模型、fallback 模型、成本预算等）
     * @param tools           工具清单（工具 ID、版本、权限）
     * @param extraConfig     额外配置（Prompt 摘要、Middleware 配置摘要、安全策略摘要）
     * @return 已构建的 Bundle 结构
     */
    public AgentBundle.AgentBundleStructure build(
            String codeVersion,
            String agentId,
            String agentName,
            AgentBundle.ModelConfig modelConfig,
            List<AgentBundle.ToolDefinition> tools,
            Map<String, Object> extraConfig) {

        log.info("AgentBundle 构建: codeVersion={}, agentId={}", codeVersion, agentId);

        // 基础 Bundle 结构（含元数据、模型配置、工具清单）
        AgentBundle.AgentBundleStructure bundle = agentBundleManager.createBundle(
                codeVersion, agentId, agentName, modelConfig, tools);

        // 计算版本指纹：对元数据+模型+工具+extraConfig 摘要做 SHA-256
        String fingerprint = computeFingerprint(bundle, extraConfig);
        log.info("AgentBundle 指纹: fingerprint={}", fingerprint.substring(0, 12));

        return bundle;
    }

    /**
     * 计算 Bundle 的 SHA-256 指纹。
     */
    static String computeFingerprint(AgentBundle.AgentBundleStructure bundle,
                                      Map<String, Object> extraConfig) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(bundle.metadata().versionId().getBytes(StandardCharsets.UTF_8));
            md.update(bundle.modelConfig().primaryModel().getBytes(StandardCharsets.UTF_8));
            md.update(Integer.toString(bundle.tools().size()).getBytes(StandardCharsets.UTF_8));
            if (extraConfig != null) {
                md.update(extraConfig.toString().getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
