package io.etclovg.codepilot.future;

/**
 * 上下文中间件基接口。
 * <p>对应书中 Ch19 §19.2 —— 上下文管理的中间件抽象。
 * <p>定义上下文处理中间件的统一契约，{@link ContextManagementMiddleware}
 * 等具体实现据此在请求链中对上下文进行增强、压缩或裁剪。
 */
public interface ContextMiddleware {

    /**
     * 处理上下文。
     *
     * @param input 输入上下文
     * @return 处理后的上下文
     */
    String process(String input);

    /**
     * 中间件名称。
     *
     * @return 名称
     */
    String name();

    /**
     * 优先级，数值越小越先执行。
     *
     * @return 优先级
     */
    default int order() {
        return 100;
    }
}
