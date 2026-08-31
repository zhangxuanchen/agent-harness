package io.etclovg.codepilot.behavior;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 事件订阅注解——标记方法为事件的订阅者。
 * <p>对应书中 Ch03 §3.4.4 —— 事件总线解耦的注解驱动订阅模式。
 *
 * <p>被 {@code @Subscribe} 标记的方法必须满足：
 * <ul>
 *   <li>方法参数恰好为一个——即要订阅的事件类型</li>
 *   <li>方法可见性为 {@code public}</li>
 * </ul>
 *
 * <p>使用方式：在组件中定义带有 {@code @Subscribe} 的方法，然后调用
 * {@link EventBus#register(Object)} 将该组件注册到事件总线。
 *
 * <pre>{@code
 * @Component
 * public class RetryOrchestrationMiddleware extends AbstractLayerMiddleware {
 *     @Subscribe
 *     public void onVerificationFailed(VerificationFailedEvent event) {
 *         if (shouldRetry(event.getStepIndex(), event.getScore())) {
 *             triggerRetry(event.getSessionId());
 *         }
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Subscribe {
}