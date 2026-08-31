package io.etclovg.codepilot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 组合过滤器：组合多个过滤器形成过滤链
 * 对应书中 Ch05 §5.3 —— 工具过滤链模式
 */
@Component
public class CompositeFilter {

    private static final Logger log = LoggerFactory.getLogger(CompositeFilter.class);

    private final List<ToolFilter> filters = new ArrayList<>();
    private final ChainStrategy strategy;

    public CompositeFilter() {
        this(ChainStrategy.ALL_PASS);
    }

    public CompositeFilter(ChainStrategy strategy) {
        this.strategy = strategy;
    }

    public void addFilter(ToolFilter filter) {
        filters.add(filter);
        log.info("[CompositeFilter] 添加过滤器: type={}, totalFilters={}",
                filter.getClass().getSimpleName(), filters.size());
    }

    public void removeFilter(ToolFilter filter) {
        filters.remove(filter);
        log.info("[CompositeFilter] 移除过滤器: type={}, remainingFilters={}",
                filter.getClass().getSimpleName(), filters.size());
    }

    public FilterChainResult apply(String toolName, String context) {
        log.debug("[CompositeFilter] 开始过滤: tool={}, filters={}", toolName, filters.size());

        List<FilterStep> steps = new ArrayList<>();
        boolean finalResult = switch (strategy) {
            case ALL_PASS -> applyAllPass(toolName, context, steps);
            case ANY_PASS -> applyAnyPass(toolName, context, steps);
            case FIRST_FAIL -> applyFirstFail(toolName, context, steps);
        };

        log.info("[CompositeFilter] 过滤完成: tool={}, result={}, steps={}",
                toolName, finalResult, steps.size());

        return new FilterChainResult(toolName, finalResult, steps, strategy);
    }

    private boolean applyAllPass(String toolName, String context, List<FilterStep> steps) {
        boolean allPass = true;
        for (ToolFilter filter : filters) {
            boolean passed = filter.test(toolName, context);
            steps.add(new FilterStep(filter.getName(), passed,
                    passed ? "通过" : "未通过"));
            if (!passed) {
                allPass = false;
                log.debug("[CompositeFilter] ALL_PASS 策略：过滤器 {} 未通过，短路",
                        filter.getName());
                break;
            }
        }
        return allPass;
    }

    private boolean applyAnyPass(String toolName, String context, List<FilterStep> steps) {
        boolean anyPass = false;
        for (ToolFilter filter : filters) {
            boolean passed = filter.test(toolName, context);
            steps.add(new FilterStep(filter.getName(), passed,
                    passed ? "通过" : "未通过"));
            if (passed) {
                anyPass = true;
                log.debug("[CompositeFilter] ANY_PASS 策略：过滤器 {} 通过，短路",
                        filter.getName());
                break;
            }
        }
        return anyPass;
    }

    private boolean applyFirstFail(String toolName, String context, List<FilterStep> steps) {
        for (ToolFilter filter : filters) {
            boolean passed = filter.test(toolName, context);
            steps.add(new FilterStep(filter.getName(), passed,
                    passed ? "通过" : "未通过"));
            if (!passed) {
                log.debug("[CompositeFilter] FIRST_FAIL 策略：过滤器 {} 首先未通过",
                        filter.getName());
                return false;
            }
        }
        return true;
    }

    public List<ToolFilter> getFilters() {
        return Collections.unmodifiableList(filters);
    }

    public ChainStrategy getStrategy() {
        return strategy;
    }

    public interface ToolFilter {
        String getName();
        boolean test(String toolName, String context);
    }

    public record FilterChainResult(
            String toolName, boolean passed,
            List<FilterStep> steps, ChainStrategy strategy
    ) {}

    public record FilterStep(String filterName, boolean passed, String detail) {}

    public enum ChainStrategy {
        ALL_PASS, ANY_PASS, FIRST_FAIL
    }
}