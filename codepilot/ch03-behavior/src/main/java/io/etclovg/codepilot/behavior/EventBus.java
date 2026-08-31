package io.etclovg.codepilot.behavior;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 简单事件总线，用于模块间解耦通信。
 * <p>对应书中 Ch03 §3.4.4 —— 行为层事件驱动的解耦机制。
 * <p>提供两种订阅方式：
 * <ul>
 *   <li><b>编程式</b>：{@link #subscribe(Class, Consumer)}，适合 lambda 表达式</li>
 *   <li><b>注解式</b>：{@link #register(Object)} + {@link Subscribe @Subscribe}，
 *       适合需要多个事件处理方法的组件（如 {@link RetryOrchestrationMiddleware}）</li>
 * </ul>
 * <p>当前为进程内同步实现，生产环境可替换为基于消息中间件的异步实现。
 */
@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Map<Class<?>, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();

    /**
     * 订阅指定类型的事件（编程式）。
     *
     * @param eventType 事件类型
     * @param handler   事件处理器
     * @param <T>       事件类型泛型
     */
    public <T> void subscribe(Class<T> eventType, Consumer<T> handler) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
        log.debug("订阅事件: type={}, handler={}", eventType.getSimpleName(), handler);
    }

    /**
     * 注册一个包含 {@link Subscribe @Subscribe} 方法的对象（注解式）。
     * <p>扫描对象中所有被 {@code @Subscribe} 标记的方法，将其参数类型作为事件类型自动订阅。
     * <p>约束：被标记的方法必须恰好有一个参数（即事件类型），且可见性为 public。
     *
     * @param subscriber 包含 {@code @Subscribe} 方法的对象
     * @throws IllegalArgumentException 如果 {@code @Subscribe} 方法的参数数量不为 1
     */
    public void register(Object subscriber) {
        for (Method method : subscriber.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Subscribe.class)) {
                continue;
            }
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length != 1) {
                throw new IllegalArgumentException(
                    "@Subscribe 方法 " + method.getName() + " 必须恰好有一个参数，当前有 " + paramTypes.length);
            }
            Class<?> eventType = paramTypes[0];
            @SuppressWarnings("unchecked")
            Consumer<Object> handler = event -> {
                try {
                    method.invoke(subscriber, event);
                } catch (Exception e) {
                    log.warn("@Subscribe 方法调用异常: method={}, error={}",
                        method.getName(), e.getMessage());
                }
            };
            subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
            log.debug("注册 @Subscribe 订阅者: type={}, method={}.{}",
                eventType.getSimpleName(), subscriber.getClass().getSimpleName(), method.getName());
        }
    }

    /**
     * 发布事件。
     * <p>同步通知所有订阅者；任一订阅者抛出异常不会中断其他订阅者。
     *
     * @param event 事件对象
     * @param <T>   事件类型泛型
     */
    @SuppressWarnings("unchecked")
    public <T> void publish(T event) {
        List<Consumer<?>> handlers = subscribers.get(event.getClass());
        if (handlers == null || handlers.isEmpty()) {
            return;
        }
        log.debug("发布事件: type={}, subscribers={}", event.getClass().getSimpleName(), handlers.size());
        for (Consumer<?> handler : handlers) {
            try {
                ((Consumer<T>) handler).accept(event);
            } catch (Exception e) {
                log.warn("事件处理异常: type={}, error={}", event.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /**
     * 清除指定事件类型的所有订阅者。
     *
     * @param eventType 事件类型
     */
    public void unsubscribeAll(Class<?> eventType) {
        subscribers.remove(eventType);
    }

    /**
     * 获取指定事件类型的订阅者数量。
     *
     * @param eventType 事件类型
     * @return 订阅者数量
     */
    public int subscriberCount(Class<?> eventType) {
        List<Consumer<?>> handlers = subscribers.get(eventType);
        return handlers == null ? 0 : handlers.size();
    }
}
