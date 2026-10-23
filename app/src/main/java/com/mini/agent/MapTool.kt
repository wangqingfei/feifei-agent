package com.mini.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 地图：标点、地名转坐标、路线规划。
 *
 * 三个动作都靠高德 Web 服务 key（`amapWebKey`），渲染地图靠 JS API key（`amapKey`）。
 * 没配对应 key 就如实说，别假装支持。
 */
class MapTool(private val ctx: Context) : Tool {

    override val name = "map"
    override val description = """地图相关：标一个位置、把地名转成坐标、规划路线。
show：标点。location 给「经度,纬度」，不给就定位当前。
geocode：把地名转成坐标，如「泰安市人民医院」。默认只报坐标文字，showMap=true 才画地图。
route：规划路线，from 和 to 可以是地名或「经度,纬度」，mode 是 driving 或 walking。
默认只返回文字（距离、时长、主要走法）；要画路线图就加 showMap=true。
什么时候别用：问「我在哪」用 location；天气用 weather。
没配高德 key 就照实说，让用户去 设置 → 高德地图 填。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("show").put("geocode").put("route"))
                        put("description", "动作，取值见 enum")
                    })
                    put("location", JSONObject().apply {
                        put("type", "string")
                        put("description", "show 用：「经度,纬度」")
                    })
                    put("address", JSONObject().apply {
                        put("type", "string")
                        put("description", "geocode 用：地名，如「泰安市人民医院」")
                    })
                    put("from", JSONObject().apply {
                        put("type", "string")
                        put("description", "route 用：起点，地名或「经度,纬度」")
                    })
                    put("to", JSONObject().apply {
                        put("type", "string")
                        put("description", "route 用：终点，地名或「经度,纬度」")
                    })
                    put("mode", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("driving").put("walking"))
                        put("description", "route 用：driving 驾车 / walking 步行")
                    })
                    put("showMap", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "geocode/show/route 时 true 才渲染地图，默认 false 只报文字")
                    })
                })
                put("required", JSONArray().put("action"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        return try {
            when (args.optString("action").trim().lowercase()) {
                "show" -> show(args)
                "geocode" -> geocode(args)
                "route" -> route(args)
                else -> "错误：action 只能是 show / geocode / route"
            }
        } catch (e: Exception) {
            "地图操作失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    private fun show(args: JSONObject): String {
        if (!MapRenderer.hasKey(ctx)) return MapRenderer.needKey()

        val loc = args.optString("location").trim()
        if (loc.isNotEmpty()) {
            val c = parseCoord(loc) ?: return "坐标格式不对，要「经度,纬度」，收到「$loc」。"
            return MapRenderer.show(ctx, c.first, c.second)
        }

        val p = LocationHelper.fix(ctx)
            ?: return "定位不到当前位置，给一个「经度,纬度」我直接标。"
        return MapRenderer.show(ctx, p.lon, p.lat)
    }

    private fun geocode(args: JSONObject): String {
        val address = args.optString("address").trim()
        if (address.isEmpty()) return "错误：geocode 需要 address（地名）。"

        val g = MapRenderer.geocode(ctx, address)
            ?: return "没查到「$address」的坐标。可能没配 Web 服务 key，或者地名太模糊。换个更具体的说法再试。"

        // 默认只报坐标，明确要地图才画——报个地名就闪地图，多数时候是打扰
        return if (args.optBoolean("showMap", false) && MapRenderer.hasKey(ctx)) {
            MapRenderer.show(ctx, g.lng, g.lat)
            "「$address」在 ${g.address}，坐标 ${String.format("%.6f,%.6f", g.lng, g.lat)}，已在地图上标出。"
        } else {
            "「$address」在 ${g.address}，坐标 ${String.format("%.6f,%.6f", g.lng, g.lat)}。" +
                "要看地图就说「标出来」。"
        }
    }

    private fun route(args: JSONObject): String {
        val from = args.optString("from").trim()
        val to = args.optString("to").trim()
        if (from.isEmpty() || to.isEmpty()) return "错误：route 需要 from 和 to。"

        val o = resolveCoord(from) ?: return "没查到起点「$from」。给个更具体的名字，或直接给「经度,纬度」。"
        val d = resolveCoord(to) ?: return "没查到终点「$to」。给个更具体的名字，或直接给「经度,纬度」。"

        val mode = args.optString("mode").trim().lowercase().ifEmpty { "driving" }
        val plan = MapRenderer.route(ctx, o.first, o.second, d.first, d.second, mode, from, to)

        // 默认只回文字；明确要画图（showMap=true）且配了 JS key 才打开地图页
        if (!args.optBoolean("showMap", false)) {
            return if (MapRenderer.hasKey(ctx)) {
                plan.text + "\n要看路线图就说「画出来」。"
            } else {
                plan.text + "\n（路线图需要 JS API Key：设置 → 高德地图。）"
            }
        }
        return plan.text + "\n" + MapRenderer.showRoute(ctx, plan)
    }

    /** 把「经度,纬度」或地名统一转成坐标对 */
    private fun resolveCoord(s: String): Pair<Double, Double>? {
        parseCoord(s)?.let { return it }
        val g = MapRenderer.geocode(ctx, s) ?: return null
        return g.lng to g.lat
    }

    private fun parseCoord(s: String): Pair<Double, Double>? {
        val parts = s.split(',', '，')
        val a = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
        val b = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
        return a to b
    }
}
