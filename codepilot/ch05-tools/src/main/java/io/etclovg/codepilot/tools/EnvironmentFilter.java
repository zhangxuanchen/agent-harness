package io.etclovg.codepilot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 环境过滤器：根据当前运行环境控制工具的可用性
 * 对应书中 Ch05 §5.3 —— 工具环境隔离
 */
@Component
public class EnvironmentFilter {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentFilter.class);

    private final Map<String, List<String>> environmentToolMap = new HashMap<>();
    private String currentEnvironment;

    public EnvironmentFilter() {
        this.currentEnvironment = "development";
        setupDefaultEnvironments();
    }

    public EnvironmentFilter(String currentEnvironment) {
        this.currentEnvironment = currentEnvironment;
        setupDefaultEnvironments();
    }

    public boolean isToolAvailable(String toolName) {
        List<String> allowedTools = environmentToolMap.get(currentEnvironment);
        if (allowedTools == null) {
            log.warn("[EnvironmentFilter] 环境 {} 无配置，默认允许所有工具", currentEnvironment);
            return true;
        }
        boolean available = allowedTools.contains(toolName);
        if (!available) {
            log.debug("[EnvironmentFilter] 工具在环境 {} 中不可用: tool={}",
                    currentEnvironment, toolName);
        }
        return available;
    }

    public EnvironmentCheckResult check(String toolName) {
        List<String> allowedTools = environmentToolMap.get(currentEnvironment);
        if (allowedTools == null) {
            return new EnvironmentCheckResult(toolName, currentEnvironment, true, "环境无限制");
        }

        boolean available = allowedTools.contains(toolName);
        return new EnvironmentCheckResult(toolName, currentEnvironment, available,
                available ? "工具在当前环境可用" : "工具在当前环境不可用");
    }

    public void configureEnvironment(String environment, List<String> allowedTools) {
        environmentToolMap.put(environment, allowedTools);
        log.info("[EnvironmentFilter] 配置环境: env={}, tools={}", environment, allowedTools);
    }

    public void setCurrentEnvironment(String environment) {
        this.currentEnvironment = environment;
        log.info("[EnvironmentFilter] 切换环境: old={}, new={}", currentEnvironment, environment);
    }

    public String getCurrentEnvironment() {
        return currentEnvironment;
    }

    public List<String> getAllowedToolsFor(String environment) {
        return environmentToolMap.getOrDefault(environment, List.of());
    }

    public Set<String> getConfiguredEnvironments() {
        return Collections.unmodifiableSet(environmentToolMap.keySet());
    }

    private void setupDefaultEnvironments() {
        environmentToolMap.put("development", List.of(
                "search", "calculate", "fetch", "write", "verify",
                "debug", "test", "mock-db", "local-cache"
        ));
        environmentToolMap.put("staging", List.of(
                "search", "calculate", "fetch", "verify",
                "staging-api", "staging-cache"
        ));
        environmentToolMap.put("production", List.of(
                "search", "calculate", "fetch", "write", "verify"
        ));
    }

    public record EnvironmentCheckResult(
            String toolName, String environment,
            boolean available, String message
    ) {}
}