package com.mini.agent

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request

/**
 * 位置解析：只求「城市名」，不求经纬度。
 *
 * 优先级：用户手填 > IP 定位（30 分钟缓存）
 * 不申请定位权限——国产 ROM 没 GMS，坐标反查城市基本失败，
 * 而天气这类场景有城市名就够了。
 */
object LocationHelper {

    private const val PREF_CITY = "ipCity"
    private const val PREF_MANUAL = "cityManual"
    private const val PREF_TS = "ipCityTs"

    /** 主动定位最多等这么久，超时就退回缓存 */
    private const val FIX_TIMEOUT_MS = 12_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 给系统提示词用的当前地点，可能为空 */
    fun current(ctx: Context): String {
        val p = AgentFactory.prefs(ctx)
        val manual = p.getString(PREF_MANUAL, "").orEmpty().trim()
        if (manual.isNotEmpty()) return manual
        return p.getString(PREF_CITY, "").orEmpty().trim()
    }

    fun isManual(ctx: Context): Boolean =
        AgentFactory.prefs(ctx).getString(PREF_MANUAL, "").orEmpty().isNotBlank()

    data class Precise(val lat: Double, val lon: Double, val accuracy: Float, val time: Long)

    /**
     * 精确坐标。只取「最后已知位置」，不做持续定位——那才是耗电大户。
     * 没权限返回 null；从来没定位过也可能返回 null（很多手机刚装 App 就是空）。
     */
    fun precise(ctx: Context): Precise? {
        if (!AppPermissions.has(ctx, AppPermissions.LOCATION)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.time > best.time) best = loc
            } catch (e: Exception) {
            }
        }
        return best?.let { Precise(it.latitude, it.longitude, it.accuracy, it.time) }
    }

    /**
     * 问一次就测一次：每次都现开 GPS/网络定位，不拿缓存凑数。
     * 缓存只当兜底——测不到（室内、超时）才退回，绝不因为「缓存够新」就跳过定位。
     *
     * 为什么不做持续定位（requestLocationUpdates 常开）：
     * 那是导航类 App 用的，位置一变就回调、一直烧电。这里要的只是「此刻在哪」的一次性答案。
     */
    fun fix(ctx: Context): Precise? {
        val cached = precise(ctx)

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return cached
        val got = AtomicReference<Location>()
        val latch = CountDownLatch(1)

        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (got.get() == null || loc.time >= (got.get()?.time ?: 0L)) got.set(loc)
                latch.countDown()
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) { latch.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }

        var started = 0
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                started++
            } catch (e: Exception) {
            }
        }
        if (started == 0) return cached

        try {
            latch.await(FIX_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
        } finally {
            try { lm.removeUpdates(listener) } catch (e: Exception) {}
        }

        val loc = got.get()
        return loc?.let { Precise(it.latitude, it.longitude, it.accuracy, it.time) } ?: cached
    }

    /** 后台刷新，失败静默；有手填值时不刷 */
    fun refreshAsync(ctx: Context) {
        val p = AgentFactory.prefs(ctx)
        if (isManual(ctx)) return
        val ts = p.getLong(PREF_TS, 0L)
        if (System.currentTimeMillis() - ts < 30 * 60_000L) return

        Thread {
            val city = fetchByIp() ?: return@Thread
            p.edit()
                .putString(PREF_CITY, city)
                .putLong(PREF_TS, System.currentTimeMillis())
                .apply()
        }.start()
    }

    private fun fetchByIp(): String? {
        return try {
            val req = Request.Builder()
                .url("https://myip.ipip.net")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                var text = String(bytes, Charsets.UTF_8)
                if (text.contains('\uFFFD')) text = String(bytes, charset("GBK"))

                // 形如：当前 IP：1.2.3.4  来自于：中国 山东 泰安  电信
                val tail = text.substringAfter("来自于：", "").trim()
                if (tail.isEmpty()) return null
                val parts = tail.split(Regex("\\s+"))
                if (parts.size < 3) return null

                val province = parts[1]
                val city = parts[2]
                val name = if (province == city || city == "北京" || city == "上海" ||
                    city == "天津" || city == "重庆"
                ) city else "$province$city"

                name.takeIf { it.isNotBlank() && it != "未知" }
            }
        } catch (e: Exception) {
            null
        }
    }
}
