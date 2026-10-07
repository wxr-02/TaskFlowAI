package com.example.taskflowai.domain.tool.impl

import com.example.taskflowai.domain.tool.Tool
import com.example.taskflowai.domain.tool.ToolContext
import com.example.taskflowai.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject

/**
 * 查询天气(信息检索类工具)—— **真实数据源**。
 *
 * 使用 Open-Meteo 免费开放 API(无需 Key、无需注册):
 * 1. 地理编码:城市中文名 → 经纬度(geocoding-api.open-meteo.com);
 * 2. 实时天气:当前温度/天气码/湿度/风速 + 今日最高最低温(api.open-meteo.com)。
 *
 * 网络失败时返回真实错误信息(绝不回退到模拟数据)。
 */
class SearchWeatherTool @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : Tool {

    override val name = "search_weather"

    override val description = "查询指定城市的实时天气(数据来自 Open-Meteo,包含当前温度、天气状况、湿度、风速和今日气温范围)。"

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "city": { "type": "string", "description": "城市名,如 北京、上海" }
          },
          "required": ["city"]
        }
    """.trimIndent()

    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(args: JSONObject, context: ToolContext): ToolResult {
        val city = args.optString("city").trim()
        if (city.isEmpty()) {
            return ToolResult.Failure("缺少参数:city")
        }

        return withContext(Dispatchers.IO) {
            try {
                fetchWeather(city)
            } catch (e: Exception) {
                ToolResult.Failure("天气数据获取失败:${e.message}")
            }
        }
    }

    private fun fetchWeather(city: String): ToolResult {
        // 1. 地理编码:城市 → 经纬度
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${URLEncoder.encode(city, "UTF-8")}&count=1&language=zh"
        val geoJson = get(geoUrl)
        val geo = json.decodeFromString<GeoResponse>(geoJson)
        val place = geo.results?.firstOrNull()
            ?: return ToolResult.Failure("没有找到城市「$city」,请换一个城市名试试")

        // 2. 实时天气(当前 + 今日范围)
        val weatherUrl = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,weather_code,relative_humidity_2m,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=1"
        val weather = json.decodeFromString<WeatherResponse>(get(weatherUrl))
        val current = weather.current
            ?: return ToolResult.Failure("天气服务返回数据异常,请重试")

        val condition = wmoCodeToChinese(current.weatherCode)
        val todayRange = if (weather.daily.temperature2mMax.isNotEmpty() && weather.daily.temperature2mMin.isNotEmpty()) {
            ",今日 ${weather.daily.temperature2mMin[0]}~${weather.daily.temperature2mMax[0]}°C"
        } else {
            ""
        }
        val text = buildString {
            append("${place.name}:$condition,当前 ${current.temperature}°C")
            append("(湿度 ${current.humidity}%,风速 ${current.windSpeed} km/h$todayRange)。")
            append("数据来源:Open-Meteo 实时接口")
        }
        return ToolResult.Success(text)
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            return response.body?.string() ?: error("响应为空")
        }
    }

    /** WMO 天气码 → 中文描述 */
    private fun wmoCodeToChinese(code: Int?): String = when (code) {
        0 -> "晴"
        1 -> "基本晴朗"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "有雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95 -> "雷暴"
        96, 99 -> "雷暴伴冰雹"
        else -> "未知天气"
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

// ===== Open-Meteo 响应模型 =====

@Serializable
private data class GeoResponse(
    val results: List<GeoPlace>? = null,
)

@Serializable
private data class GeoPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

@Serializable
private data class WeatherResponse(
    val current: CurrentWeather? = null,
    val daily: DailyWeather = DailyWeather(),
)

@Serializable
private data class CurrentWeather(
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("relative_humidity_2m") val humidity: Int? = null,
    @SerialName("wind_speed_10m") val windSpeed: Double? = null,
)

@Serializable
private data class DailyWeather(
    @SerialName("temperature_2m_max") val temperature2mMax: List<Double> = emptyList(),
    @SerialName("temperature_2m_min") val temperature2mMin: List<Double> = emptyList(),
)
