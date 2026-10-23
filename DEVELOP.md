# 飞飞 · 开发指南（v0.71）

> 配套 `START-HERE.md`。那份说"这是什么"，这份说"怎么改"。
> 目标：接手后不用读 9000 行源码就能动手。

---

## 1. 代码地图

57 个 Kotlin 文件，全在 `app/src/main/java/com/mini/agent/`。按职责分组：

### 核心链路（改这几个的概率最高）

| 文件 | 行数 | 职责 | 改它的时候注意 |
|---|---|---|---|
| `AgentLoop.kt` | 483 | ReAct 循环本体：调模型 → 解析 tool_calls → 执行工具 → 回灌结果 | 埋点在 `trace()`；`turnTrace` 每轮清空 |
| `LlmClient.kt` | 231 | SSE 流式请求模型 | `chatStream` 的 `onReasoning` 是 v0.71 新加的，别删 |
| `ToolRegistry.kt` | 466 | `Tool` 接口定义 + 5 个内置工具（web_search/web_fetch/remember/notify/save） | `run()` **永不抛异常**是铁律 |
| `AgentFactory.kt` | 115 | 工具注册表 + 循环工厂 | **加工具要在这里注册**（见 §3） |
| `MainActivity.kt` | 970 | 聊天界面全部逻辑 | 见 §5 的界面约定 |
| `Models.kt` | 76 | `ChatMessage` 等数据类 | `toJson()` 不含 trace，`toHistoryJson()` 含 —— 两条路径别混 |

### 工具实现

`WebSearchTool`/`WebFetchTool` 在 `ToolRegistry.kt`；其余各自独立文件：
`WeatherTool` `EmailTools` `FileTool`(file_write/download) `NativeDownloadTool` `ImageTool` `MapTool` `MapRenderer`
`LocationTool`+`LocationHelper` `SmsTool` `PhoneTool` `ContactTool` `NotificationsTool` `NotifyListenerService`
`TaskTool`+`TaskStore`+`TaskScheduler`+`AlarmReceiver`+`DailyWorker` `TasksActivity`
`NetdiskTool` `ShareTool` `SelfTool` `JsTool`+`JsRunner`+`JsBridge` `WebAppTool`+`LocalServer`+`WebViewActivity`
`CustomTools`(自定义 HTTP 工具) `QrCode`(自写二维码) `EmailClient` `ExaClient`

### 基础设施

| 文件 | 职责 |
|---|---|
| `Store.kt` | 会话读写（`files/agent/sessions/{id}.json`）、摘要压缩 |
| `AgentConfig.kt` | `config.json` 读写、多 provider/模型清单、SharedPreferences 迁移 |
| `PromptStore.kt` | 系统提示词（分层：策略/能力/反馈） |
| `Markdown.kt` `CodeBlockSpan.kt` `CodeCardView.kt` | 消息渲染（代码块卡片） |
| `AgentFaceView.kt` | 首页小飞（Canvas 绘制） |
| `TraceEvent.kt` `TraceView.kt` | 过程记录（v0.71） |
| `CrashLog.kt` `FeifeiApp.kt` | 崩溃日志（v0.71） |
| `NotificationHelper.kt` `NotifyPermission.kt` `NotificationStore.kt` | 通知 |
| `AppPermissions.kt` | 权限申请 |
| `RunLog.kt` | `log.jsonl` 环形日志 |

### 资源

`res/layout/`：`activity_main.xml`（聊天）`activity_settings.xml`（设置）`activity_tasks.xml`（定时任务）
`res/xml/file_paths.xml`（FileProvider）
`res/drawable/`：`bg_trace_card.xml` 等；颜色在 `values/colors.xml`

---

## 2. 加一个新工具（最常做的事）

**第 1 步**：写实现类，继承 `Tool` 接口（定义在 `ToolRegistry.kt` 顶部）：

```kotlin
class MyTool(private val ctx: Context) : Tool {
    override val name = "my_tool"                       // 模型看到的就是这个名字
    override val description = "干什么用，一句话"          // 模型靠它决定要不要调
    override val sensitive = false                       // 返回内容含隐私就置 true

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("url", JSONObject().apply {
                put("type", "string")
                put("description", "参数说明")
            })
        })
        put("required", JSONArray().put("url"))
    }

    override fun execute(args: JSONObject): String {
        val url = args.optString("url")
        if (url.isEmpty()) return "错误：缺少 url"       // ← 返回错误文本，别抛异常
        return "成功的结果文本"
    }
}
```

**第 2 步**：在 `AgentFactory.registry()` 的 `listOf(...)` 里加一行 `MyTool(ctx),`

**第 3 步**：完成。工具的 `schema()` 会自动进请求体，`execute()` 返回值会自动回灌给模型。

### 必须遵守的 5 条

1. **`execute()` 永不抛异常** —— 抛出去 = App 崩 = 用户看到一屏红色。`ToolRegistry.run()` 已经包了一层 try/catch 并转成文本，但自己 catch 更好。
2. **返回值是给模型看的** —— 用自然语言写清「成功/失败/为什么」，模型会据此改参数重试。
3. **`sensitive = true`** —— 返回电话、短信正文、凭据时必须置位，日志里只记长度不记内容。
4. **不硬编码 Key** —— 走 `AgentConfig` 读 `config.json`。
5. **Android API 26 兼容**（minSdk 26）—— 比如下载用 `setDestinationInExternalFilesDir` 而不是 `setDestinationInExternalPublicDir`，省掉权限申请。

---

## 3. 改界面的约定

聊天界面**没有 RecyclerView**，是 `ScrollView + LinearLayout` 手工建 View。

- **助手消息 = 卡片**：头部（"飞飞" + 复制按钮）+ 正文层 `currentAssistantContent`
- **用户消息 = 右侧橙色气泡**，左侧挂复制按钮
- **每条消息一个独立 `StringBuilder`**（`currentAssistantSrc`），**不要用全局缓冲** —— v0.71 前就是全局共用一个 `streamBuf`，导致复制按钮永远只复制最新那条
- 加新控件：在 `activity_main.xml` 写布局 → `MainActivity` 里 `findViewById` → 手工 `addView`
- 布局里写 `xmlns:android="http://schemas.android.com/apk/res/android"`（写成 `res/android` 会 lint 致命报错）

---

## 4. 自检清单（改完必须跑）

```bash
export JAVA_HOME=/root/.sdkman/candidates/java/current
export ANDROID_HOME=/opt/android-sdk
export PATH=$ANDROID_HOME/build-tools/34.0.0:$PATH

cd feifei-v0.71
/opt/gradle-8.7/bin/gradle assembleRelease --no-daemon --console=plain
```

**通过标准（三条都要过）：**

```bash
# 1) 编译成功，无 lint 致命错误
#    BUILD SUCCESSFUL

# 2) 签名正确（覆盖安装必须同一把钥匙）
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
# 期望 SHA-256: fc2b76ee788d916520b82a29b72466779d4ab4ddaa685b69947cd51195a4bb0c

# 3) 版本号对得上（改 versionCode 后必查）
aapt dump badging app/build/outputs/apk/release/app-release.apk | grep versionCode
# 期望 versionCode='71'
```

**改完记得升版本号**：`app/build.gradle` 里 `versionCode` + `versionName` 都要改，然后
`cp app/build/outputs/apk/release/app-release.apk MiniAgent-0.72.apk`。

**git 提交**（本包已内置 git 基线）：

```bash
git add -A && git commit -m "v0.72 xxx"
git log --oneline        # 看演进
git diff HEAD~1          # 看这次改了什么
```

> `build/`、`.gradle/`、`*.apk` 已在 `.gitignore` 里，不会被提交。

---

## 5. 数据与配置

App 私有目录 `files/agent/`（**在手机上，不在源码包里**）：

```
config.json        接口 + 模型清单 + 运行参数
system.md          系统提示词，删掉恢复默认
memory.md          长期记忆，一行一条
tasks.json         定时任务
custom_tools.json  自建 HTTP 工具
sessions/{id}.json 每段对话一个
crash.log          崩溃日志（v0.71）
log.jsonl          运行日志，环形 300 条
```

**首次使用要配接口**：装上 APK → 设置页 → 填 baseUrl / apiKey / model → 「测试」。
源码里**不含任何真实 Key**（`AgentFactory.DEFAULT_BASE` 只是默认 baseUrl，Key 留空），
这是刻意的，别把 Key 写回源码。

**关于 API Key 存在哪**：只在手机上的 `files/agent/config.json` 和 SharedPreferences(`cfg`) 里。
换手机/重装要重填。这符合「不要把 API Key 硬编码进 APK，反解就能拿到」。

---

## 6. 改提示词

提示词在 `files/agent/system.md`（手机里，不在源码包），分层：**策略 / 能力 / 反馈**。
改提示词要**真机测**——模型行为受措辞影响很大，改完问同样的问题对比效果。
`docs/probe_models.py` 是模型探测脚本，可以用来批量对比。

---

## 7. 版本演进脉络（简版，完整版见 `HISTORY.md` §5）

> **改提示词或工具描述之前，先读 `HISTORY.md` §1–§3。**
> 那里记录了三个实测踩出来的坑（模型编造期刊名、不知道今天几号、发布时间丢失）、
> 工具描述的四条实测发现（给数值样例会教模型编数据等），以及做过又撤掉的功能。
> 这些是唯一记录，源码里看不出来，丢了不会再有第二次。

| 阶段 | 版本 | 做了什么 |
|---|---|---|
| 基础 | 0.1–0.3 | 对话 + 工具调用 + SSE 流式 |
| 工具 | 0.4–0.16 | 搜索、Exa、界面重做、邮箱、提示词外置 |
| 能力 | 0.17–0.27 | JS 执行、web_app、多会话、预算感知 |
| 领域 | 0.28–0.48 | 位置、地图、通知、定时任务、模型切换、会话压缩 |
| 打磨 | 0.49–0.63 | 小飞形象、代码块、主题、交互细节 |
| 修 bug | 0.64–0.68 | 通知读取、会话多选删、短信排序 |
| 新能力 | 0.69–0.71 | 二维码生成、下载统一、复制+过程记录+崩溃日志 |

**节奏**：平均每个版本 1–2 小时，一次只做一件事。

---

## 8. 容易踩的坑（重复强调第 4 次，因为真的常错）

| 现象 | 原因 | 解法 |
|---|---|---|
| `Val cannot be reassigned` | `apply { text = "x" }` 外层有同名 `val text`，**局部变量名遮蔽了 receiver 属性** | 写 `this.text = "x"` |
| lint `NamespaceTypo` 致命 | drawable 写了 `res/android` | 必须是 `http://schemas.android.com/apk/res/android` |
| lint `Property must be initialized` | `private val x: TextView` 在 init 里赋值不合法 | 改 `private lateinit var x: TextView` |
| lint `versionCode: Int is deprecated` | — | 加 `@Suppress("DEPRECATION")` |
| lint `Unsafe use of a nullable receiver of type Any?` | `v.toString()` 没判空 | `if (v == null \|\| v == JSONObject.NULL) "" else v.toString()` |
| 构建 4 秒挂，init 脚本语法错 | `/root/.gradle/init.gradle` 与 `FAIL_ON_PROJECT_REPOS` 冲突 | 删掉或改名 `init.gradle.disabled` |
| Gradle 下载卡住 | `services.gradle.org` 跳 GitHub 国内不通 | 用华为镜像（wrapper 已配好） |
| 打包 `META-INF/LICENSE.md` 重复 | JavaMail 两 jar 冲突 | 已在 `packaging.resources.excludes` 配好 |
| 覆盖安装后数据全丢 | 换了签名钥匙 | 必须用 `app/personal.jks`（口令 `minipass`，别名 `mini`） |
| 复制按钮复制到别的消息 | 用了全局共享的 `StringBuilder` | 每条消息一个独立的 |

---

## 9. 接手第一件事

**别急着写代码。** 按这个顺序：

1. 构建一次，确认能出包（§4）
2. 装到手机上，跑一遍：发一句话 → 看过程记录面板展开 → 故意触发一个崩溃（设置页看崩溃日志）→ 复制一条消息
3. 把验证结果写进交接文档（哪些真机过了、哪些还坏着）
4. 只修坏的，再加新功能

**这个项目已经攒了一批「编译通过但没跑过」的代码** —— 已知问题表里高优先级那两条（几乎所有功能未真机验证、v0.71 三新功能未验证）就是这个意思。
在没有真机验证的情况下连着改三个功能，是这个项目最容易翻车的方式。

---

最后更新：2026-10-23
对应版本：v0.71（versionCode 71）
