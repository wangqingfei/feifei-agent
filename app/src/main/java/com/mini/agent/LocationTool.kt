package com.mini.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 查当前位置：精确坐标（有权限时）+ 城市（IP 兜底）。
 *
 * 不做地理围栏、不做持续轨迹——那要前台服务和大量耗电，是另一个量级的东西。
 * 这里只回答「我现在在哪」，供模型判断「离哪多远」这类一次性问题。
 */
class LocationTool(private val ctx: Context) : Tool {

    override val name = "location"
    override val sensitive = true
    override val description = """查用户当前的位置。
什么时候用：用户问「我在哪」「离 XX 多远」「到 XX 要多久」「附近有什么」。
什么时候别用：天气已经自带地点了，用 weather；问历史去过哪、轨迹、常去地点——我们没有，直说没有。
返回坐标和城市名：每次都现开 GPS 测一次（最多等 12 秒），是实时定位，不用缓存凑数。
测不到（室内/超时）才退回旧的缓存，并会明说。
默认只报文字；用户说「开地图」「标在地图上」才传 showMap=true 渲染地图。
没权限或测不到就直说，别编坐标。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("showMap", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "true 才渲染地图；默认 false，只报文字位置")
                    })
                })
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val sb = StringBuilder("当前位置：\n")

        val city = LocationHelper.current(ctx)
        if (city.isNotEmpty()) {
            sb.append("· 城市：").append(city)
            sb.append(if (LocationHelper.isManual(ctx)) "（手填）" else "（IP 定位）").append("\n")
        } else {
            sb.append("· 城市：未知\n")
        }

        if (!AppPermissions.has(ctx, AppPermissions.LOCATION)) {
            sb.append("· 精确坐标：没有定位权限。")
            sb.append("让用户去 设置 → 隐私权限 开「定位（精确）」，开了之后才有经纬度。")
            return sb.toString()
        }

        // fix = 每次都现开 GPS 测一次，缓存只当测不到时的兜底
        val p = LocationHelper.fix(ctx)
        if (p == null) {
            sb.append("· 精确坐标：测不到。可能在室内、GPS 没开，或者系统返回空。")
            sb.append("让用户到窗边或室外、确认定位开关开着，再问一次。")
            return sb.toString()
        }

        sb.append("· 坐标：").append(String.format("%.6f, %.6f", p.lat, p.lon)).append("\n")
        sb.append("· 精度：约 ").append(p.accuracy.toInt()).append(" 米\n")

        // 配了 Web 服务 key 就逆地理出文字地址，比光给坐标有用得多
        val addr = MapRenderer.reverseGeocode(ctx, p.lon, p.lat)
        if (addr != null) sb.append("· 具体位置：").append(addr).append("\n")

        val ageMin = (System.currentTimeMillis() - p.time) / 60_000L
        if (ageMin < 1) {
            sb.append("· 刚刚定位（实时测的）")
        } else {
            sb.append("· 现场测不到，用了 ").append(ageMin).append(" 分钟前的缓存")
        }

        // 用户明确要地图才渲染，平时只报文字——「定位」多数时候只是想知道自己在哪
        if (args.optBoolean("showMap", false) && MapRenderer.hasKey(ctx)) {
            MapRenderer.show(ctx, p.lon, p.lat)
            sb.append("\n· 地图：已在 App 内打开")
        }
        return sb.toString()
    }
}
