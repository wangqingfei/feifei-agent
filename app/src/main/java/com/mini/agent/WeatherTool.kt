package com.mini.agent

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 天气：直接用 wttr.in 的结构化数据，比「搜网页再总结」准得多。
 * 不给 city 时用 LocationHelper 识别到的城市。
 */
class WeatherTool(private val ctx: Context) : Tool {

    override val name = "weather"
    override val description = """查天气：当前温度、体感、天气、湿度、风速、今明温度与降水概率。
不传城市就优先用精确定位（坐标查，精确到乡镇），拿不到再退回手填/城市名，不要反问他在哪。
只支持今天和明天；空气质量、紫外线没有。
数字只能来自本工具的实际返回，不许凭记忆报。"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("city", JSONObject().apply {
                        put("type", "string")
                        put("description", "城市名，例 泰安。留空用当前位置")
                    })
                })
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val city = args.optString("city").trim()

        // 显式给了城市：按城市查
        if (city.isNotEmpty()) return fetchCity(city)

        // 没给：优先用精确坐标查（精确到乡镇），拿不到再退回「手填城市 > IP 城市」
        val p = LocationHelper.fix(ctx)
        if (p != null) return fetchCoord(p.lat, p.lon, LocationHelper.current(ctx))

        val fallback = LocationHelper.current(ctx)
        if (fallback.isEmpty()) return "不知道你在哪个城市。在设置里填一下位置，或直接说要查哪里的天气。"
        return fetchCity(fallback)
    }

    private fun fetchCity(city: String): String {
        return try {
            val url = "https://wttr.in/" + URLEncoder.encode(city, "UTF-8") + "?format=j1&lang=zh"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "curl/8.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "天气服务返回 HTTP ${resp.code}，可以改用 web_search 搜「$city 天气」。"
                val body = resp.body?.string().orEmpty()
                parse(city, body)
            }
        } catch (e: Exception) {
            "天气服务连不上（${e.javaClass.simpleName}）。可以改用 web_search 搜「$city 天气」。"
        }
    }

    /** 按经纬度查。wttr.in 的坐标顺序是「纬度,经度」，和它文档一致 */
    private fun fetchCoord(lat: Double, lon: Double, fallbackName: String): String {
        return try {
            val url = "https://wttr.in/${lat},${lon}?format=j1&lang=zh"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "curl/8.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "天气服务返回 HTTP ${resp.code}，可以改用 web_search。"
                val body = resp.body?.string().orEmpty()
                // 坐标查询会返回最近气象站的名称，比手填/城市名更贴近实际位置
                parse(areaName(body, fallbackName), body)
            }
        } catch (e: Exception) {
            "天气服务连不上（${e.javaClass.simpleName}）。可以改用 web_search。"
        }
    }

    /** 从坐标查询的返回里取最近地点名，拿不到就退回传入的兜底名 */
    private fun areaName(body: String, fallback: String): String {
        return try {
            val root = JSONObject(body)
            val area = root.optJSONArray("nearest_area")
                ?.optJSONObject(0)
                ?.optJSONArray("areaName")
                ?.optJSONObject(0)
                ?.optString("value")
                .orEmpty()
            if (area.isNotBlank()) area else fallback
        } catch (e: Exception) {
            fallback
        }
    }

    private fun parse(city: String, body: String): String {
        return try {
            val root = JSONObject(body)
            val cur = root.getJSONArray("current_condition").getJSONObject(0)
            val days = root.optJSONArray("weather") ?: JSONArray()

            val sb = StringBuilder()
            sb.append(city).append(" 当前 ")
                .append(cur.optString("temp_C")).append("°C")
                .append("（体感 ").append(cur.optString("FeelsLikeC")).append("°C），")
                .append(desc(cur.optString("weatherCode")))
            sb.append("，湿度 ").append(cur.optString("humidity")).append("%")
            sb.append("，风 ").append(cur.optString("windspeedKmph")).append(" km/h\n")

            val labels = arrayOf("今天", "明天", "后天")
            for (i in 0 until minOf(days.length(), 2)) {
                val d = days.getJSONObject(i)
                sb.append(labels.getOrElse(i) { "第${i + 1}天" }).append(" ")
                    .append(d.optString("mintempC")).append("~")
                    .append(d.optString("maxtempC")).append("°C")

                rain(d)?.let { sb.append("，降水概率 ").append(it).append("%") }
                sb.append("\n")
            }
            sb.toString().trim()
        } catch (e: Exception) {
            "天气数据没解析出来（${e.javaClass.simpleName}），可以改用 web_search 搜「$city 天气」。"
        }
    }

    /** 取全天最高的降水概率，比平均值有意义 */
    private fun rain(day: JSONObject): Int? {
        val hourly = day.optJSONArray("hourly") ?: return null
        var max = -1
        for (i in 0 until hourly.length()) {
            val v = hourly.getJSONObject(i).optString("chanceofrain", "").toIntOrNull() ?: continue
            if (v > max) max = v
        }
        return if (max >= 0) max else null
    }

    private fun desc(code: String): String = when (code) {
        "113" -> "晴"
        "116" -> "多云"
        "119", "122" -> "阴"
        "143", "248", "260" -> "雾"
        "176", "293", "296" -> "小雨"
        "179", "323", "326" -> "小雪"
        "182", "185", "281", "284", "311", "314", "317", "320", "362", "365" -> "雨夹雪"
        "200", "386", "389" -> "雷阵雨"
        "227", "230", "392", "395" -> "雷雪"
        "263", "266" -> "毛毛雨"
        "299", "302" -> "中雨"
        "305", "308", "356", "359" -> "大雨"
        "329", "332", "335", "338", "368", "371" -> "中到大雪"
        "350", "374", "377" -> "冰粒"
        "353" -> "阵雨"
        else -> "未知"
    }
}
