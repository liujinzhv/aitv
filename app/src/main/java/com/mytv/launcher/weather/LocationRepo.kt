package com.mytv.launcher.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.mytv.launcher.Prefs
import com.mytv.launcher.util.Async
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class LocatedPlace(
    val city: String,
    val latitude: Double,
    val longitude: Double,
    val source: String
)

/**
 * 天气定位：
 * 1) 有定位权限时取系统最后已知位置（网络 / GPS / PASSIVE）；
 * 2) 再用 Geocoder 反查城市；
 * 3) 电视没有定位能力时，退回 IP 定位（ip-api 中文优先，ipapi.co 兜底）。
 * 结果写入 Prefs，24 小时内复用，避免每次回桌面都重新定位。
 */
object LocationRepo {

    private const val FRESH_MS = 24 * 60 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun cachedPlace(): LocatedPlace? {
        if (Prefs.locationAt <= 0L) return null
        return LocatedPlace(Prefs.city, Prefs.latitude, Prefs.longitude, "缓存")
    }

    fun isFresh(): Boolean = System.currentTimeMillis() - Prefs.locationAt < FRESH_MS

    fun resolve(ctx: Context, force: Boolean = false, callback: (LocatedPlace?) -> Unit) {
        Async.io {
            callback(locateBlocking(ctx, force))
        }
    }

    /** 同步定位，供后台线程与天气请求复用 */
    fun locateBlocking(ctx: Context, force: Boolean = false): LocatedPlace? {
        if (!Prefs.autoLocation && !force) return cachedPlace()
        if (!force && isFresh()) return cachedPlace()

        val system = locateBySystem(ctx)
        val place = if (system != null) {
            describe(ctx, system.latitude, system.longitude)?.copy(source = "系统定位")
        } else {
            locateByIp()
        }

        if (place != null) {
            Prefs.city = place.city
            Prefs.latitude = place.latitude
            Prefs.longitude = place.longitude
            Prefs.locationAt = System.currentTimeMillis()
        }
        return place
    }

    private fun hasLocationPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun locateBySystem(ctx: Context): Location? {
        if (!hasLocationPermission(ctx)) return null
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = try {
            manager.allProviders
        } catch (t: Throwable) {
            return null
        }
        var best: Location? = null
        for (provider in providers) {
            val location = try {
                manager.getLastKnownLocation(provider)
            } catch (t: Throwable) {
                null
            } ?: continue
            if (best == null || location.time > best!!.time) best = location
        }
        // 超过 30 天的位置参考价值不大，交给 IP 定位
        if (best != null && System.currentTimeMillis() - best.time > 30L * 24 * 60 * 60 * 1000) {
            return null
        }
        return best
    }

    private fun describe(ctx: Context, lat: Double, lon: Double): LocatedPlace? {
        val fallbackCity = Prefs.city.ifBlank { "本地" }
        var city = fallbackCity
        if (Geocoder.isPresent()) {
            val geocoder = Geocoder(ctx, Locale.CHINA)
            val address = try {
                geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
            } catch (t: Throwable) {
                null
            }
            if (address != null) {
                city = address.locality
                    ?: address.subAdminArea
                    ?: address.adminArea
                    ?: city
            }
        }
        return LocatedPlace(city, lat, lon, "系统定位")
    }

    private fun locateByIp(): LocatedPlace? {
        ipApi()?.let { return it }
        return ipApiCo()
    }

    /** http://ip-api.com 免费接口，city 直接是中文；仅用于粗略城市级定位。 */
    private fun ipApi(): LocatedPlace? {
        return try {
            val req = Request.Builder()
                .url("http://ip-api.com/json/?lang=zh-CN&fields=status,country,regionName,city,lat,lon")
                .header("User-Agent", "MyTV/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string().orEmpty())
                if (json.optString("status") != "success") return null
                val lat = json.optDouble("lat", Double.NaN)
                val lon = json.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return null
                val city = json.optString("city").ifBlank {
                    json.optString("regionName").ifBlank { "本地" }
                }
                LocatedPlace(city, lat, lon, "IP 定位")
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun ipApiCo(): LocatedPlace? {
        return try {
            val req = Request.Builder()
                .url("https://ipapi.co/json/")
                .header("User-Agent", "MyTV/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string().orEmpty())
                val lat = json.optDouble("latitude", Double.NaN)
                val lon = json.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return null
                LocatedPlace(
                    json.optString("city").ifBlank { Prefs.city.ifBlank { "本地" } },
                    lat,
                    lon,
                    "IP 定位"
                )
            }
        } catch (t: Throwable) {
            null
        }
    }
}
