package io.etclovg.codepilot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 白名单过滤器：只允许白名单内的工具通过
 * 对应书中 Ch05 §5.3 —— 工具访问控制
 */
@Component
public class AllowListFilter {

    private static final Logger log = LoggerFactory.getLogger(AllowListFilter.class);

    private Set<String> allowedTools;

    public AllowListFilter() {
        this.allowedTools = Set.of("search", "calculate", "fetch", "write", "verify");
    }

    public AllowListFilter(Set<String> allowedTools) {
        this.allowedTools = allowedTools;
    }

    public boolean isAllowed(String toolName) {
        boolean allowed = allowedTools.contains(toolName);
        if (!allowed) {
            log.debug("[AllowListFilter] 工具被拒绝: tool={}, allowed={}",
                    toolName, allowedTools);
        }
        return allowed;
    }

    public FilterResult filter(String toolName) {
        if (allowedTools.contains(toolName)) {
            return new FilterResult(true, toolName, "工具在白名单内");
        }
        log.warn("[AllowListFilter] 工具被拦截: tool={}, allowedList={}",
                toolName, allowedTools);
        return new FilterResult(false, toolName, "工具不在白名单内: " + toolName);
    }

    public void setAllowedTools(Set<String> allowedTools) {
        this.allowedTools = allowedTools;
        log.info("[AllowListFilter] 白名单已更新: count={}", allowedTools.size());
    }

    public Set<String> getAllowedTools() {
        return allowedTools;
    }

    public void addTool(String toolName) {
        if (!allowedTools.contains(toolName)) {
            var mutable = new java.util.HashSet<>(allowedTools);
            mutable.add(toolName);
            allowedTools = Set.copyOf(mutable);
            log.info("[AllowListFilter] 添加白名单工具: {}", toolName);
        }
    }

    public void removeTool(String toolName) {
        if (allowedTools.contains(toolName)) {
            var mutable = new java.util.HashSet<>(allowedTools);
            mutable.remove(toolName);
            allowedTools = Set.copyOf(mutable);
            log.info("[AllowListFilter] 移除白名单工具: {}", toolName);
        }
    }

    public record FilterResult(boolean passed, String toolName, String reason) {}
}