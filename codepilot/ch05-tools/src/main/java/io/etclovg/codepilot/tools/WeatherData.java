package io.etclovg.codepilot.tools;

/**
 * 天气数据记录。
 * <p>对应书中 Ch05 §5.3 —— 天气工具的结构化数据模型。
 * <p>描述单个城市的当前天气状态，由 {@link WeatherTool} 解析外部 API 响应后填充，
 * 用于生成 {@link WeatherResponse}。
 *
 * @param city        城市名称
 * @param temperature 温度（摄氏度）
 * @param humidity    湿度百分比
 * @param windSpeed   风速（km/h）
 * @param condition   天气状况描述（如 晴、雨）
 * @param feelsLike   体感温度
 */
public record WeatherData(
        String city,
        double temperature,
        int humidity,
        double windSpeed,
        String condition,
        double feelsLike
) {

    /**
     * 构造带默认字段的天气数据。
     *
     * @param city        城市
     * @param temperature 温度
     * @param condition   天气状况
     * @return 天气数据
     */
    public static WeatherData of(String city, double temperature, String condition) {
        return new WeatherData(city, temperature, 50, 0.0, condition, temperature);
    }
}
