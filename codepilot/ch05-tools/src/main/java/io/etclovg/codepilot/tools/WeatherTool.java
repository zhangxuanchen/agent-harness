package io.etclovg.codepilot.tools;

import io.agentscope.core.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 天气查询工具。
 * <p>对应书中 Ch05 §5.3 —— 示例工具，演示 Agent 如何调用外部 API。
 */
@Component
public class WeatherTool {

    /**
     * 获取指定城市的天气。
     */
    @Tool(description = "获取指定城市的当前天气信息")
    public String getWeather(String city) {
        return "城市 " + city + " 当前天气：晴，温度 22°C";
    }

    /**
     * 获取天气预报。
     */
    @Tool(description = "获取未来几天的天气预报")
    public String getForecast(String city, int days) {
        return city + " 未来 " + days + " 天天气预报";
    }
}