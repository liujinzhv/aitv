package com.mytv.launcher.weather

import android.content.Context
import com.mytv.launcher.Prefs
import com.mytv.launcher.R
import com.mytv.launcher.util.Async
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class WeatherInfo(
    val city: String,
    val temp: Int,
    val code: Int,
    val desc: String,
    val high: Int,
    val low: Int,
    val humidity: Int,
    val updatedAt: Long
)

object WeatherRepo {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun cached(): WeatherInfo? {
        val raw = Prefs.lastWeather
        if (raw.isBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            WeatherInfo(
                city = json.optString("city", Prefs.city),
                temp = json.optInt("temp"),
                code = json.optInt("code"),
                desc = json.optString("desc"),
                high = json.optInt("high"),
                low = json.optInt("low"),
                humidity = json.optInt("humidity"),
                updatedAt = json.optLong("updatedAt")
            )
        }.getOrNull()
    }

    fun refresh(ctx: Context, forceLocate: Boolean = false, callback: (WeatherInfo?) -> Unit) {
        Async.io {
            if (Prefs.autoLocation) {
                LocationRepo.locateBlocking(ctx, force = forceLocate)
            }
            val info = request()
            if (info != null) {
                Prefs.lastWeather = JSONObject().apply {
                    put("city", info.city)
                    put("temp", info.temp)
                    put("code", info.code)
                    put("desc", info.desc)
                    put("high", info.high)
                    put("low", info.low)
                    put("humidity", info.humidity)
                    put("updatedAt", info.updatedAt)
                }.toString()
                Prefs.lastWeatherAt = info.updatedAt
            }
            if (ctx is android.app.Activity) {
                ctx.runOnUiThread { callback(info) }
            } else {
                callback(info)
            }
        }
    }

    private fun request(): WeatherInfo? {
        val lat = Prefs.latitude
        val lon = Prefs.longitude
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=Asia%2FShanghai&forecast_days=1"
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "MyTV/1.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val current = json.getJSONObject("current")
                val daily = json.getJSONObject("daily")
                val code = current.optInt("weather_code", 0)
                val temp = current.optDouble("temperature_2m", 0.0).roundToInt()
                val humidity = current.optInt("relative_humidity_2m", 0)
                val high = daily.getJSONArray("temperature_2m_max").optDouble(0, temp.toDouble()).roundToInt()
                val low = daily.getJSONArray("temperature_2m_min").optDouble(0, temp.toDouble()).roundToInt()
                WeatherInfo(
                    city = Prefs.city,
                    temp = temp,
                    code = code,
                    desc = descOf(code),
                    high = high,
                    low = low,
                    humidity = humidity,
                    updatedAt = System.currentTimeMillis()
                )
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun descOf(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大部晴朗"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
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
        80 -> "阵雨"
        81 -> "强阵雨"
        82 -> "暴雨"
        85 -> "阵雪"
        86 -> "强阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷暴冰雹"
        else -> "多云"
    }

    fun iconOf(code: Int): Int = when (code) {
        0, 1 -> R.drawable.ic_sun
        2, 3 -> R.drawable.ic_cloud
        45, 48 -> R.drawable.ic_fog
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_snow
        95, 96, 99 -> R.drawable.ic_thunder
        else -> R.drawable.ic_cloud
    }
}
