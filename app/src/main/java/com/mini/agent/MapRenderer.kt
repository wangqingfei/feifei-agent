package com.mini.agent

import android.content.Context
import android.content.Intent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 把经纬度渲染成一张高德地图网页，用飞飞自己的本地服务器 + 内置浏览器打开。
 *
 * 复用 web_app 那套（LocalServer + WebViewActivity），不新增任何基础设施。
 *
 * Key 从 prefs 读，不硬编码——JS API 的 key 虽然是前端明文、理论上藏不住，
 * 但硬编码进 APK 等于反编译即得，而且会跟着源码一起进交接包，没必要。
 */
object MapRenderer {

    private const val KEY_AMAP = "amapKey"
    private const val KEY_SEC = "amapSecurity"
    private const val KEY_WEB = "amapWebKey"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun hasKey(ctx: Context): Boolean =
        AgentFactory.prefs(ctx).getString(KEY_AMAP, "").orEmpty().isNotBlank()

    fun needKey(): String =
        "还没配高德地图 Key。请让用户去 设置 → 高德地图，把 JS API 的 Key 和安全密钥填进去。"

    /**
     * 经纬度 → 文字地址（逆地理编码）。
     * 走 Web 服务 REST，和 JS API 是两种 Key——网页渲染用 JS key，这里用 Web 服务 key。
     * 没配 Web key 返回 null，调用方自己降级（只报坐标，不报地址）。
     */
    fun reverseGeocode(ctx: Context, lng: Double, lat: Double): String? {
        val key = AgentFactory.prefs(ctx).getString(KEY_WEB, "").orEmpty().trim()
        if (key.isEmpty()) return null

        val lngS = String.format("%.6f", lng)
        val latS = String.format("%.6f", lat)
        return try {
            val url = "https://restapi.amap.com/v3/geocode/regeo" +
                "?location=$lngS,$latS&key=$key&extensions=base&radius=500"
            val req = Request.Builder().url(url).header("User-Agent", "feifei").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = r.body?.string() ?: return null
                val o = try { JSONObject(body) } catch (e: Exception) { return null }
                if (o.optString("status") != "1") return null
                o.optJSONObject("regeocode")?.optString("formatted_address")
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 生成地图页并打开。返回给模型看的说明（不是给用户看的）。
     */
    fun show(ctx: Context, lng: Double, lat: Double): String {
        val key = AgentFactory.prefs(ctx).getString(KEY_AMAP, "").orEmpty().trim()
        if (key.isEmpty()) return needKey()
        val sec = AgentFactory.prefs(ctx).getString(KEY_SEC, "").orEmpty().trim()

        return try {
            val html = buildHtml(key, sec, lng, lat)
            val dir = LocalServer.rootDir(ctx)
            File(dir, "map.html").writeText(html)
            val port = LocalServer.start(ctx)

            ctx.startActivity(Intent(ctx, WebViewActivity::class.java).apply {
                putExtra("port", port)
                putExtra("path", "map.html")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "地图已在 App 内打开，标记点是刚才的位置。"
        } catch (e: Exception) {
            "地图渲染失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    /**
     * 内嵌高德 JS API 2.0 的单页地图：
     * 一个标记点 + 逆地理编码后的地址文案。样式脚本全内联，手机竖屏。
     */
    data class Geo(val lng: Double, val lat: Double, val address: String)

    /** 地名 → 坐标（地理编码）。没配 Web key 返回 null */
    fun geocode(ctx: Context, address: String): Geo? {
        val key = AgentFactory.prefs(ctx).getString(KEY_WEB, "").orEmpty().trim()
        if (key.isEmpty()) return null
        return try {
            val url = "https://restapi.amap.com/v3/geocode/geo" +
                "?address=" + URLEncoder.encode(address, "UTF-8") + "&key=$key"
            val req = Request.Builder().url(url).header("User-Agent", "feifei").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = r.body?.string() ?: return null
                val o = try { JSONObject(body) } catch (e: Exception) { return null }
                if (o.optString("status") != "1") return null
                val g = o.optJSONArray("geocodes")?.optJSONObject(0) ?: return null
                val loc = g.optString("location").split(',')
                val lng = loc.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
                val lat = loc.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
                Geo(lng, lat, g.optString("formatted_address"))
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 路线规划。只做驾车和步行，公交要城市代码、且结构复杂，先不做。
     * 文字版（距离、时长、主要走法）一定给；points 是折线点串，
     * 用来画地图——空列表表示响应里没有 polyline，调用方别画。
     */
    data class RoutePlan(
        val text: String,
        val points: List<Pair<Double, Double>>,
        val from: Pair<Double, Double>,
        val to: Pair<Double, Double>,
        val mode: String,
        val distKm: String,
        val durMin: Int,
        val fromLabel: String = "起点",
        val toLabel: String = "终点"
    )

    fun route(
        ctx: Context,
        oLng: Double, oLat: Double, dLng: Double, dLat: Double,
        mode: String,
        fromLabel: String = "起点", toLabel: String = "终点"
    ): RoutePlan {
        val key = AgentFactory.prefs(ctx).getString(KEY_WEB, "").orEmpty().trim()
        if (key.isEmpty()) return RoutePlan(needKey(), emptyList(), oLng to oLat, dLng to dLat, mode, "0", 0, fromLabel, toLabel)

        val endpoint = if (mode == "walking") "walking" else "driving"
        val oL = String.format("%.6f", oLng)
        val oA = String.format("%.6f", oLat)
        val dL = String.format("%.6f", dLng)
        val dA = String.format("%.6f", dLat)
        return try {
            val url = "https://restapi.amap.com/v3/direction/$endpoint" +
                "?origin=$oL,$oA&destination=$dL,$dA&key=$key&extensions=base"
            val req = Request.Builder().url(url).header("User-Agent", "feifei").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    return RoutePlan("路线服务返回 HTTP ${r.code}", emptyList(), oLng to oLat, dLng to dLat, mode, "0", 0, fromLabel, toLabel)
                }
                val body = r.body?.string().orEmpty()
                parseRoute(body, mode, oLng to oLat, dLng to dLat, fromLabel, toLabel)
            }
        } catch (e: Exception) {
            RoutePlan("路线服务连不上（${e.javaClass.simpleName}）", emptyList(), oLng to oLat, dLng to dLat, mode, "0", 0, fromLabel, toLabel)
        }
    }

    private fun parseRoute(
        body: String, mode: String,
        from: Pair<Double, Double>, to: Pair<Double, Double>,
        fromLabel: String, toLabel: String
    ): RoutePlan {
        return try {
            val o = JSONObject(body)
            if (o.optString("status") != "1") {
                return RoutePlan("没规划出路线（info=${o.optString("info")}）。换步行/驾车试试，或换个目的地。", emptyList(), from, to, mode, "0", 0, fromLabel, toLabel)
            }
            val path = o.optJSONObject("route")?.optJSONArray("paths")?.optJSONObject(0)
                ?: return RoutePlan("没规划出路线。", emptyList(), from, to, mode, "0", 0, fromLabel, toLabel)
            val distM = path.optString("distance").toIntOrNull() ?: 0
            val durS = path.optString("duration").toIntOrNull() ?: 0
            val distKm = String.format("%.1f", distM / 1000.0)
            val durMin = (durS + 59) / 60

            val sb = StringBuilder()
            sb.append(if (mode == "walking") "步行 " else "驾车 ")
            sb.append(distKm).append(" 公里，约 ").append(durMin).append(" 分钟\n")
            sb.append("主要走法：\n")

            val steps = path.optJSONArray("steps")
            if (steps != null) {
                // 只挑有转向说明的步骤，避免一堆「直行 300 米」刷屏
                var shown = 0
                for (i in 0 until steps.length()) {
                    if (shown >= 6) break
                    val st = steps.optJSONObject(i) ?: continue
                    val ins = st.optString("instruction").trim()
                    if (ins.isEmpty()) continue
                    sb.append(shown + 1).append(". ").append(ins).append("\n")
                    shown++
                }
            }

            // 折线：每个 step 的 polyline 是 "lng,lat;lng,lat;..."，按顺序拼起来
            val points = ArrayList<Pair<Double, Double>>()
            if (steps != null) {
                for (i in 0 until steps.length()) {
                    val pl = steps.optJSONObject(i)?.optString("polyline") ?: continue
                    for (seg in pl.split(';')) {
                        val c = seg.split(',')
                        val lng = c.getOrNull(0)?.trim()?.toDoubleOrNull() ?: continue
                        val lat = c.getOrNull(1)?.trim()?.toDoubleOrNull() ?: continue
                        points.add(lng to lat)
                    }
                }
            }
            RoutePlan(sb.toString().trimEnd(), points, from, to, mode, distKm, durMin, fromLabel, toLabel)
        } catch (e: Exception) {
            RoutePlan("路线数据没解析出来（${e.javaClass.simpleName}）", emptyList(), from, to, mode, "0", 0, fromLabel, toLabel)
        }
    }

    /**
     * 把路线画成地图页并打开。需要 JS API key（渲染）——Web 服务 key 只规划不画图。
     * 没配 key 或没有折线数据时如实返回，调用方保留文字版。
     */
    fun showRoute(ctx: Context, plan: RoutePlan): String {
        if (!hasKey(ctx)) return needKey()
        if (plan.points.isEmpty()) return "路线数据里没有折线，画不出来，上面文字版就是全部信息。"

        val key = AgentFactory.prefs(ctx).getString(KEY_AMAP, "").orEmpty().trim()
        val sec = AgentFactory.prefs(ctx).getString(KEY_SEC, "").orEmpty().trim()

        return try {
            val html = buildRouteHtml(key, sec, plan)
            val dir = LocalServer.rootDir(ctx)
            File(dir, "route.html").writeText(html)
            val port = LocalServer.start(ctx)

            ctx.startActivity(Intent(ctx, WebViewActivity::class.java).apply {
                putExtra("port", port)
                putExtra("path", "route.html")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "路线图已在 App 内打开（${if (plan.mode == "walking") "步行" else "驾车"} ${plan.distKm} 公里，约 ${plan.durMin} 分钟）。"
        } catch (e: Exception) {
            "路线图渲染失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    private fun buildHtml(key: String, sec: String, lng: Double, lat: Double): String {
        // securityJsCode 只有在控制台给这个 key 配了安全密钥时才必填；空也不碍事
        val secParam = if (sec.isNotEmpty()) "&securityJsCode=$sec" else ""
        val lngS = String.format("%.6f", lng)
        val latS = String.format("%.6f", lat)

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
  html,body,#map{width:100%;height:100%;margin:0;padding:0}
  #info{position:absolute;left:10px;top:10px;right:10px;background:rgba(255,255,255,.93);
    padding:8px 12px;border-radius:8px;font:13px/1.5 sans-serif;color:#333;z-index:999;
    box-shadow:0 1px 4px rgba(0,0,0,.12)}
</style>
<script src="https://webapi.amap.com/maps?v=2.0&key=$key$secParam&plugin=AMap.Geocoder"></script>
</head>
<body>
<div id="map"></div>
<div id="info">定位中…</div>
<script>
  var pos = [$lngS, $latS];
  var map = new AMap.Map('map', { zoom: 16, center: pos, resizeEnable: true });
  var marker = new AMap.Marker({ position: pos, title: '当前位置' });
  map.add(marker);
  try {
    var geocoder = new AMap.Geocoder({});
    geocoder.getAddress(pos, function(status, result) {
      if (status === 'complete' && result.regeocode && result.regeocode.formattedAddress) {
        document.getElementById('info').innerText = '你在：' + result.regeocode.formattedAddress;
      } else {
        document.getElementById('info').innerText = '坐标 $lngS, $latS';
      }
    });
  } catch (e) {
    document.getElementById('info').innerText = '坐标 $lngS, $latS';
  }
</script>
</body>
</html>
""".trimIndent()
    }

    /**
     * 路线图：整条折线 + 起点「起」/ 终点「终」标记 + 顶部信息条，自动缩放看全。
     * 驾车用主题暖橙，步行用蓝，和地图页（map.html）样式保持一致。
     */
    private fun buildRouteHtml(key: String, sec: String, plan: RoutePlan): String {
        val secParam = if (sec.isNotEmpty()) "&securityJsCode=$sec" else ""
        val lineColor = if (plan.mode == "walking") "#3D6FB4" else "#C4693F"
        val modeName = if (plan.mode == "walking") "步行" else "驾车"

        // 点串：「经度,纬度」的 JS 数组字面量
        val pts = plan.points.joinToString(",") { "[${String.format("%.6f", it.first)},${String.format("%.6f", it.second)}]" }

        // 标签做基础转义，防止用户输入的地名里带引号把 JS 撑爆
        fun esc(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        val fLabel = esc(plan.fromLabel)
        val tLabel = esc(plan.toLabel)

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
  html,body,#map{width:100%;height:100%;margin:0;padding:0}
  #info{position:absolute;left:10px;top:10px;right:10px;background:rgba(255,255,255,.93);
    padding:8px 12px;border-radius:8px;font:13px/1.5 sans-serif;color:#333;z-index:999;
    box-shadow:0 1px 4px rgba(0,0,0,.12)}
  #info b{color:$lineColor}
  .lbl{font:bold 12px/1 sans-serif;color:#fff;background:#333;border-radius:10px;padding:3px 7px;
    white-space:nowrap;box-shadow:0 1px 3px rgba(0,0,0,.2)}
</style>
<script src="https://webapi.amap.com/maps?v=2.0&key=$key$secParam"></script>
</head>
<body>
<div id="map"></div>
<div id="info"><b>$modeName</b>　${plan.distKm} 公里 · 约 ${plan.durMin} 分钟<br>$fLabel → $tLabel</div>
<script>
  var path = [$pts];
  var map = new AMap.Map('map', { resizeEnable: true });
  var line = new AMap.Polyline({
    path: path, strokeColor: '$lineColor', strokeWeight: 8, strokeOpacity: 0.9,
    lineJoin: 'round', lineCap: 'round', showDir: true
  });
  map.add(line);
  var from = new AMap.Marker({ position: path[0], anchor: 'bottom-center',
    label: { content: '起', offset: [0, -2] } });
  var to = new AMap.Marker({ position: path[path.length - 1], anchor: 'bottom-center',
    label: { content: '终', offset: [0, -2] } });
  map.add([from, to]);
  map.setFitView([line, from, to]);
</script>
</body>
</html>
""".trimIndent()
    }
}
