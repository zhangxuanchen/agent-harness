package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Agent Bundle 注册表。
 * <p>对应书中 Ch16 §16.2 —— 管理所有 Agent Bundle 的注册和版本。
 */
@Component
public class AgentBundleRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentBundleRegistry.class);

    private final AgentBundle agentBundle;

    public AgentBundleRegistry(AgentBundle agentBundle) {
        this.agentBundle = agentBundle;
    }

    /**
     * 注册 Agent Bundle 结构。
     */
    public void register(AgentBundle.AgentBundleStructure bundle) {
        agentBundle.createBundle(
                bundle.metadata().versionId(),
                bundle.metadata().agentId(),
                bundle.metadata().agentName(),
                bundle.modelConfig(),
                bundle.tools()
        );
        log.info("[BundleRegistry] 注册: id={}", bundle.metadata().versionId());
    }

    /**
     * 注销 Agent Bundle。
     */
    public boolean unregister(String bundleId) {
        agentBundle.deleteBundle(bundleId);
        return true;
    }

    /**
     * 获取 Bundle。
     */
    public Optional<AgentBundle.AgentBundleStructure> get(String bundleId) {
        return agentBundle.getBundle(bundleId);
    }

    /**
     * 列出所有 Bundle。
     */
    public Collection<AgentBundle.AgentBundleStructure> listAll() {
        return agentBundle.getStatistics().values().stream()
                .map(Object::toString)
                .map(s -> agentBundle.getBundle(s).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }
}