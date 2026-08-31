package io.etclovg.codepilot.foundation;

import io.agentscope.core.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 示例工具集。
 * <p>对应书中附录 B —— Agent 可使用的示例工具集合。
 * <p>展示如何为 Agent 注册自定义工具：方法上加 AgentScope {@link Tool} 注解，
 * 再通过 {@code Toolkit.registerTool(new MyTools())} 反射注册。
 */
@Component
public class MyTools {

    /**
     * 计算两个数之和。
     */
    @Tool(description = "计算两个数字的和")
    public double add(double a, double b) {
        return a + b;
    }

    /**
     * 获取当前时间。
     */
    @Tool(description = "获取当前系统时间")
    public String currentTime() {
        return java.time.LocalDateTime.now().toString();
    }

    /**
     * 转换温度单位。
     */
    @Tool(description = "摄氏度转华氏度")
    public double celsiusToFahrenheit(double celsius) {
        return celsius * 9.0 / 5.0 + 32.0;
    }
}
