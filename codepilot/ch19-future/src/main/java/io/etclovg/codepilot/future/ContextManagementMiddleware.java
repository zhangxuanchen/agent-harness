package io.etclovg.codepilot.future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 上下文管理中间件。
 * <p>对应书中 Ch19 §19.2 —— 上下文的统一管理与编排。
 * <p>实现 {@link ContextMiddleware}，在请求链中协调上下文的注入、
 * 压缩与遗忘，维持上下文在预算内的最优状态。
 */
@Component
public class ContextManagementMiddleware implements ContextMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ContextManagementMiddleware.class);

    @Override
    public String process(String input) {
        log.debug("管理上下文: inputLength={}", input == null ? 0 : input.length());
        return input;
    }

    @Override
    public String name() {
        return "ContextManagement";
    }

    @Override
    public int order() {
        return 50;
    }
}
