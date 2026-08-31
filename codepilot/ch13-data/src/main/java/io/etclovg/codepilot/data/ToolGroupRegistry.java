package io.etclovg.codepilot.data;

import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具组注册中心。
 * <p>对应书中 Ch13 §13.2 —— 工具复用的工程实践（工具组 + Meta-Tool）。
 * <p>按功能域分组管理工具，运行时通过 Meta-Tool 动态激活/停用工具组，
 * 控制工具可见性——Agent 在某次任务中只看到对应域的工具组，而非全部工具，
 * 缓解工具数量爆炸导致的选错率上升。共享注册中心作为统一服务发现机制。
 *
 * <p><b>概念示例</b>：方法签名对齐章节调用。{@link Toolkit} 来自 agentscope-core
 * （本模块已引入该依赖）。实际章节示例中调用 {@code group.tools().forEach(merged::register)}
 * 组装工具组，但本桩实现不实际合并工具，仅维护激活集合。
 */
@Component
public class ToolGroupRegistry {

    private final Map<String, Toolkit> groups = new ConcurrentHashMap<>();
    private final Set<String> activeGroups = ConcurrentHashMap.newKeySet();

    /**
     * 注册一个功能域工具组。
     *
     * @param domain  功能域
     * @param toolkit 工具组
     */
    public void registerGroup(String domain, Toolkit toolkit) {
        groups.put(domain, toolkit);
    }

    /**
     * Meta-Tool：运行时激活某功能域的工具组。
     *
     * @param domain 功能域
     */
    public void activate(String domain) {
        activeGroups.add(domain);
    }

    /**
     * 停用某功能域的工具组。
     *
     * @param domain 功能域
     */
    public void deactivate(String domain) {
        activeGroups.remove(domain);
    }

    /**
     * 组装当前激活的工具组为一个 Toolkit 供 Agent 使用。
     *
     * @return 合并后的工具组（桩实现返回空 Toolkit）
     */
    public Toolkit activeToolkit() {
        Toolkit merged = new Toolkit();
        // 概念示例：实际应遍历 activeGroups，将每个 group 的工具注册到 merged：
        //   for (String domain : activeGroups) {
        //       Toolkit group = groups.get(domain);
        //       if (group != null) group.tools().forEach(merged::register);
        //   }
        // 此处为桩实现，直接返回空 Toolkit。
        return merged;
    }
}
