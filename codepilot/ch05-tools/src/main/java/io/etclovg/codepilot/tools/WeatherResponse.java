package io.etclovg.codepilot.tools;

import java.time.Instant;
import java.util.List;

/**
 * 天气 API 响应记录。
 * <p>对应书中 Ch05 §5.3 —— 天气查询工具的 API 响应封装。
 * <p>包含当前天气与可选的预报列表，由 {@link WeatherTool} 返回给 Agent。
 *
 * @param data        当前天气数据
 * @param forecast    未来天气预报列表（可为空）
 * @param source      数据来源
 * @param fetchedAt   获取时间
 * @param success     是否成功
 * @param errorMessage 错误信息（失败时）
 */
public record WeatherResponse(
        WeatherData data,
        List<WeatherData> forecast,
        String source,
        Instant fetchedAt,
        boolean success,
        String errorMessage
) {

    /**
     * 构造成功响应。
     *
     * @param data     当前天气
     * @param forecast 预报列表
     * @param source   数据来源
     * @return 成功响应
     */
    public static WeatherResponse success(WeatherData data, List<WeatherData> forecast, String source) {
        return new WeatherResponse(data, forecast, source, Instant.now(), true, null);
    }

    /**
     * 构造失败响应。
     *
     * @param errorMessage 错误信息
     * @return 失败响应
     */
    public static WeatherResponse failure(String errorMessage) {
        return new WeatherResponse(null, List.of(), "unknown", Instant.now(), false, errorMessage);
    }
}
