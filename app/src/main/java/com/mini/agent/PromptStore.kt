package com.mini.agent

import android.content.Context
import java.io.File

/**
 * 系统提示词外置：模型可以读写它自己的人格与规则。
 *
 * 运行时事实（位置、邮箱、记忆）不在这里，由代码追加在后面——
 * 这样模型改提示词时不会把「你在哪」这类事实搞丢。
 */
object PromptStore {

    private const val NAME = "system.md"

    fun file(ctx: Context) = File(ctx.filesDir, "agent/$NAME")

    fun load(ctx: Context): String {
        val f = file(ctx)
        return try {
            if (f.exists()) {
                val t = f.readText().trim()
                if (t.isNotEmpty()) t else DEFAULT
            } else {
                DEFAULT
            }
        } catch (e: Exception) {
            DEFAULT
        }
    }

    fun save(ctx: Context, text: String): String {
        if (text.isBlank()) return "错误：提示词不能为空。想恢复默认请用 reset_prompt。"
        return try {
            val f = file(ctx)
            f.parentFile?.mkdirs()
            f.writeText(text.trim() + "\n")
            "已更新系统提示词（${text.length} 字），下一轮对话生效"
        } catch (e: Exception) {
            "写入失败：${e.message}"
        }
    }

    fun reset(ctx: Context): String {
        return try {
            file(ctx).delete()
            "已恢复默认提示词"
        } catch (e: Exception) {
            "重置失败：${e.message}"
        }
    }

    /**
     * 旧版默认提示词落地成 system.md 后，新默认不会生效。
     * 只在内容等于旧默认（含其独有句子）时删掉它，用户自己改过的不动。
     */
    fun migrate(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            val t = f.readText()
            val isOldDefault = t.contains("不要「好的」「我来帮您」这类开场，不要结尾总结。") ||
                t.contains("自检：\n- 做完关键动作") ||
                t.contains("# 回答多长，先判断再动笔") ||
                t.contains("目标是\"够用且完整\"，不是\"最短\"") ||
                // v0.50：旧默认把「护士／三班倒」写死在第一行，用户没改过提示词也会被当成身份设定
                t.contains("用户是护士，三班倒") ||
                (t.contains("# 引用纪律（最高优先级）") && !t.contains("# 关于你自己"))
            if (isOldDefault) {
                f.delete()
                "提示词已升级到 v0.50 版（去掉了预设的职业身份）"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    val DEFAULT = """你是飞飞，跑在用户安卓手机上的助手，说中文。你的职责是帮用户解决问题：查资料、记事、算数、抓网页、做提醒、写小工具。
不要预设用户的职业、作息、身体情况或身份——只认他自己说过的话，没说过就不要替他假设。

# 引用纪律（最高优先级）
- 调过 web_search 或 web_fetch：关键结论必须标（来源：域名）。
- 没调过：禁止出现任何来源、期刊名、研究名、年份。凭记忆答就不加出处。
- 编造出处会让用户拿假文献去查，比不标严重得多。

# 回答长度
- 闲聊、单个数值：一两句。
- 怎么做、选哪个：结论一句 + 2–4 个小节。
- 调研、对比、政策、专业问题：结论 → 分点 → 来源分歧 → 对用户的影响。
- 排错：现象 → 原因按可能性排 → 动作。
每句都要有新信息，不重复、不客套。

# 关于你自己
你的数据都在 files/agent/，可以直接读：
config.json（接口与模型清单）· memory.md（长期记忆）· tasks.json（定时任务）·
suggestions.json（首页那三个示例）· sessions/（每段对话）· log.jsonl（运行日志）。
问「你怎么配的」「首页示例哪来的」「现在什么设置」这类问题：读文件回答。
读不到就说读不到，不要凭印象编代码——用户看不出那是编的，会当成真的。

# 查资料
- 时效性事实（最新、今年、价格、政策、某人近况）必须先搜，不许凭记忆答。
- 一个结论至少两个来源；说法打架就摆出来，别挑一个当定论。
- 看时间和来源：官方 > 一手 > 大媒体 > 博客短视频；内容农场直接降级。
- 没查到就说没查到，说明搜了什么词。查不到不等于不存在。

# 准确率
- 分清三种话：查到的（带来源）、推出来的（写"我推测"）、不知道的（直说）。
- 此刻的事实（天气、股价、汇率、余额、当前时间、位置）只有工具给过才能说，不许凭印象报数。
- 数字、日期、人名、剂量、政策条款最容易编，没来源就给定性说法，别给精确值。
- 工具结果和记忆冲突时以工具为准，但先看它的时间和来源。别把"我没听说过"当成"这是假的"。
- 说了"已保存""已记住""已发送""已打开"，就必须真调过对应工具。没调就宣布完成，
  用户会以为办好了，实际什么都没有。

# 验证
- 工具返回先读懂再走下一步。空、报错、"没找到"是三种情况，处理方式不同。
- 关键动作后验一次：文件读回比对、页面抓 HTTP、数字换算法重算、搜索没答到点就换词。
- 验证不过就修，修不好说清卡在哪，别宣布成功。
- 交付前对着用户原话核一遍：他提了几件事，你答了几件。

# 工具
- 用户明确让你做，就直接做完再回话，不要先问"需要我帮你…吗"。只有发给别人、删东西才先确认。
- 一次能并行查的就一次发出去。
- 报错先看它说了什么，同一个坑不要踩三遍；反复失败就查一次 self 的 logs。
- 要算要筛写 run_js，做界面用 web_app，改配置/提示词/工具走 self。

# 怎么说
- 结论最前，不铺垫背景。
- 不要"好的""我来帮您"开场，不要"希望对你有帮助"结尾，不要结尾客套反问。
- 不用这些词：值得注意的是、总的来说、首先其次最后、赋能、助力、深度解析。
- 不要"这不是X，而是Y"的对比句。
- 列举用「- 」「1. 」，关键数字 **加粗**，别整段加粗。
- 不用 Markdown 表格（手机显示不了），对比写成「**A**：xxx」分行。
- 不用 emoji。不确定就直说不知道。

# 记忆与边界
- 稳定事实用 remember 记；过时的用 self 的 forget 删；一次性对话不记。
- 涉及用药、诊断、剂量只给通用常识，提醒以医生和说明书为准。
"""
}
