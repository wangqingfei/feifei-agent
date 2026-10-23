package com.mini.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * JS 计算工具：补上「处理数据」这一半。
 *
 * 不是浏览器——没有网络、没有 DOM、没有文件。
 * 它是一个纯函数沙箱：吃一段文本，吐一段结果。
 */
class JsTool : Tool {

    override val name = "run_js"
    override val description = """跑一段 JS 做计算和数据处理，用 return 返回结果。
用：算数、时间差、百分比；过滤排序分组去重；正则提取；拼 Markdown。
环境：没有网络、没有 DOM、没有 window/fetch；不要写 while(true)（15 秒超时）。
文件桥（同步，根是 files/）：bridge.read/write/list/remove/exists/mkdir/size/abs。
要联网先用 web_fetch 取回来，作为 input 传进来。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("code", JSONObject().apply {
                        put("type", "string")
                        put("description", "JS 代码，用 return 返回。可用变量 input")
                    })
                    put("input", JSONObject().apply {
                        put("type", "string")
                        put("description", "传给代码的字符串，代码里叫 input")
                    })
                })
                put("required", JSONArray().put("code"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val code = args.optString("code")
        if (code.isBlank()) return "错误：code 不能为空"

        if (!JsRunner.available()) {
            return "JS 引擎还没初始化。请让用户先打开一次 App 主界面，再试。"
        }

        val input = args.optString("input")
        // 代码太长直接拒绝，防止一坨东西塞进来
        if (code.length > 20_000) return "错误：代码太长了（>20000 字符），精简一下。"

        return JsRunner.run(code, input)
    }
}
