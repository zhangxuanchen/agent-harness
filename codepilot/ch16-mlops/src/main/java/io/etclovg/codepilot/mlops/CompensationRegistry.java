package io.etclovg.codepilot.mlops;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 补偿操作注册表。对应书中 Ch16 §16.3.2。
 * <p>工具注册时声明"正向操作 -> 补偿操作"映射；回滚时按工具名取补偿操作逆序执行。
 */
@Component
public class CompensationRegistry {

    private final Map<String, CompensationAction> actions = new ConcurrentHashMap<>();

    /** 注册：工具名 -> 补偿操作。 */
    public void register(String toolName, CompensationAction action) {
        actions.put(toolName, action);
    }

    /** 取工具对应的补偿操作；未注册抛 IllegalStateException。 */
    public CompensationAction get(String toolName) {
        CompensationAction action = actions.get(toolName);
        if (action == null) {
            throw new IllegalStateException("未注册补偿操作: " + toolName);
        }
        return action;
    }
}
