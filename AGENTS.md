# AGENTS.md — 给 AI 接手指引

> **如果你是一个 AI 助手（或新对话的助手），接管这个项目前先读这里。**
> 这是最小必要信息 + 硬性规则。详细文档见文末三份 md。

## 这是什么

安卓原生 App「飞飞」（包名 `com.mini.agent`）：纯 Kotlin + XML View，自写 ReAct 单循环 + 24 个工具，
跑在手机上，模型走任何 OpenAI 兼容接口。**个人自用工具，不是产品。**

## 接手口令（可直接复制给新对话）

```
读 AGENTS.md、START-HERE.md、DEVELOP.md、HISTORY.md，我们继续做「飞飞」这个安卓 App。
```

## 关键路径

| | |
|---|---|
| 源码 | `app/src/main/java/com/mini/agent/`（57 个 kt） |
| 循环本体 | `AgentLoop.kt`（483 行）—— 调模型 → 解析 tool_calls → 执行 → 回灌 |
| 工具接口 | `ToolRegistry.kt` 顶部 `interface Tool`（5 个成员：name/description/schema/execute/sensitive） |
| 工具注册 | `AgentFactory.kt` 的 `registry()` —— **加工具改这里** |
| 聊天界面 | `MainActivity.kt`（970 行，ScrollView + LinearLayout 手工建 View） |
| 模型请求 | `LlmClient.kt`（SSE 流式） |
| 配置 | `AgentConfig.kt` → 手机上 `files/agent/config.json` |

## 构建

```bash
export JAVA_HOME=<JDK17+路径>
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleRelease
# 仓库不含签名钥匙：有 app/personal.jks 就用它签，没有则出 unsigned 包
```

**改完必须三条自检**：① `BUILD SUCCESSFUL` 无 lint 致命错
② `apksigner verify --print-certs` 指纹 = `fc2b76ee788d916520b82a29b72466779d4ab4ddaa685b69947cd51195a4bb0c`
③ `aapt dump badging | grep versionCode` 与 `app/build.gradle` 一致

## 硬性规则（违反 = 项目质量退化）

1. **`ToolRegistry.run()` 永不抛异常** —— 抛出去 = App 崩 = 用户一屏红色。失败转成文本回给模型，它才有机会自己改参数重试。
2. **不要把 API Key 写进源码或硬编码** —— Key 只存在手机的 `config.json`。
3. **每条消息用独立 `StringBuilder`** —— 不要全局共享缓冲（v0.71 踩过：复制按钮只复制最新那条）。
4. **`toJson()`（发给模型）与 `toHistoryJson()`（落盘）严格分离** —— trace 只走落盘路径，不污染模型上下文。
5. **敏感工具设 `sensitive = true`** —— 电话/短信正文只记日志长度不记内容（`log.jsonl` 是明文，模型自己也能读）。
6. **局部变量名会遮蔽 receiver 同名属性** —— `apply { text = "x" }` 外层有 `val text` 会编译报错，必须写 `this.text = "x"`。
7. **drawable XML 的 `xmlns:android` 必须写全** `http://schemas.android.com/apk/res/android`（少一个字符 lint 致命）。
8. **不引入 agent 框架** —— 200 行循环够用，引框架只会让排障变难。
9. **一次只改一个功能，改完升版本号、交付 APK**。
10. **不要在没有真机验证的情况下连着改三个功能** —— 这个项目已经攒了一批「编译通过但没跑过」的代码。

## 当前状态（v0.72）

- **未真机验证**（高优先级）：定时任务、web_app、run_js、通讯录/拨号/短信、通知读取、
  地图白名单（高德 JS key 需加 127.0.0.1/localhost）、v0.71 三件套（复制/过程记录/崩溃日志）。
- **v0.35 时期实测过 12 项**，数据在 `HISTORY.md` §6。
- **路线图**：`make_pdf`（`android.graphics.pdf.PdfDocument`）、会话重命名/长按删单个、语音输入。

## 三份文档

| 文件 | 内容 |
|---|---|
| `START-HERE.md` | 这是什么：功能清单、已知问题、下一步 |
| `DEVELOP.md` | 怎么改：代码地图、加工具模板、界面约定、自检命令 |
| `HISTORY.md` | 为什么这么做：提示词实测坑、工具描述四条发现、做过又撤掉的、完整演进 |

## 改提示词或工具描述前必读

`HISTORY.md` §1–§3。那里记录了模型编造期刊名、不知道今天几号、
"给数值样例会教模型编数据"等**实测踩出来的坑**——这些是唯一记录，源码里看不出来。
