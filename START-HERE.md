# 飞飞智能体 · 接手第一页（v0.72）

> 给下一个对话看的。三分钟读完，不用翻历史。

## 一句话

安卓原生 App（`com.mini.agent`），纯 Kotlin + XML View，自己写的 ReAct 单循环 + **24 个工具**，跑在手机上，模型走任何 OpenAI 兼容接口。**自用工具，不是产品。**

装机：`MiniAgent-0.72.apk`（Android 8.0+，2.8 MB，已签名）。

## 怎么接上（最重要）

**沙箱按会话隔离**，新对话看不到旧对话的文件，源码只能靠这个包传。

1. 解压得到 `feifei-v0.71/`
2. 新对话第一句：

```
读 feifei-v0.71/START-HERE.md、DEVELOP.md、HISTORY.md，我们继续做「飞飞」这个安卓 App。
```

3. 缺环境按下面装

**三份文档怎么分工：**

| 文档 | 回答什么 | 什么时候看 |
|---|---|---|
| `START-HERE.md`（本文件） | 这**是什么**：怎么构建、24 个工具、已知问题、下一步 | 接手第一遍 |
| `DEVELOP.md` | 怎么**改**：57 个文件各干什么、怎么加工具、自检命令、踩坑表 | 动手前 |
| `HISTORY.md` | 为什么**这么做**：提示词实测坑、工具描述四条发现、做过又撤掉的、完整版本演进 | 改提示词/工具描述前，或想重做某个功能前 |

> 本包只含**源码 + 三份文档 + v0.72 APK**。`build/`、`.gradle/`、历史 APK、临时 wheel 等中间产物已全部删除。
> 源码已带 git 基线（`git log` 可看演进，`git diff HEAD~1` 可看改了什么）。

### 装完先做这三步

1. **设置页填接口**：baseUrl / apiKey / model → 点「测试」。
   源码里**不含任何真实 Key**（`AgentFactory.DEFAULT_BASE` 只是默认 baseUrl，Key 留空）——这是刻意的，别把 Key 写回源码。
   Key 只存在手机上的 `files/agent/config.json` 和 SharedPreferences(`cfg`)，换手机要重填。
2. **跑一遍冒烟测试**：发一句话 → 展开「过程记录」面板 → 复制一条消息 → 设置页看崩溃日志。
3. **再考虑改代码**。


## 构建

```bash
export JAVA_HOME=/root/.sdkman/candidates/java/current
export ANDROID_HOME=/opt/android-sdk
cd feifei-v0.71
/opt/gradle-8.7/bin/gradle assembleRelease --no-daemon --console=plain
# 产物：app/build/outputs/apk/release/app-release.apk
```

缺环境时：

```bash
# Gradle 8.7（services.gradle.org 会 302 到 GitHub，国内不通，走华为镜像）
curl -fL -o /tmp/g.zip https://mirrors.huaweicloud.com/gradle/gradle-8.7-bin.zip && unzip -q /tmp/g.zip -d /opt
# Android SDK
mkdir -p /opt/android-sdk/cmdline-tools
curl -fL -o /tmp/c.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q /tmp/c.zip -d /opt/android-sdk/cmdline-tools
mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

**三个必踩的坑**

| 现象 | 原因 | 解法 |
|---|---|---|
| 构建 4 秒挂，报 init 脚本语法错 | `/root/.gradle/init.gradle` 与 `settings.gradle` 的 `FAIL_ON_PROJECT_REPOS` 冲突 | 删掉或改名 `init.gradle.disabled` |
| Gradle 下载卡住 | `services.gradle.org` 跳 GitHub，国内不通 | 换华为镜像 |
| 打包报 `META-INF/LICENSE.md` 重复 | JavaMail 两 jar 冲突 | 已在 `packaging.resources.excludes` 配好 |

签名：`app/personal.jks`，口令 `minipass`，别名 `mini`。
**覆盖安装必须同一把钥匙**，换了会被当成另一个 App，数据全丢。
证书 SHA-256：`fc2b76ee788d916520b82a29b72466779d4ab4ddaa685b69947cd51195a4bb0c`

## v0.66 → v0.71 改了什么

| 版本 | 内容 |
|---|---|
| v0.66 | 修通知读取被过滤；接入 `MapRenderer/MapTool` |
| v0.67 | 会话多选、批量删除 |
| v0.68 | 修短信排序（`date=0` 的商业验证码永远沉底 → 改为多键排序） |
| v0.69 | 自写二维码生成器（ZXing 不可用，GF(256) 溢出/RS 方向/模式指示符等 5 个 bug 已修，zxingcpp 5 case 全解码通过） |
| v0.70 | 合并两个下载实现为 `download` + `mode`（auto/direct/system），底层 OkHttp + DownloadManager 双实现 |
| **v0.71** | **① 对话可复制 ② 内部循环/思考/操作记录可视（可折叠、流式、默认收起） ③ 崩溃日志本地捕获** |

### v0.71 三个功能的实现要点

**① 可复制**
每条消息一个独立 `StringBuilder`（原为全局共用一个 `streamBuf`，导致复制按钮只复制最新那条）。
助手卡片头部 + 用户气泡各挂一个「复制」按钮，不走长按选字（和「链接可点」抢触摸事件，只能二选一）。
`copyAnswer(source)` 闭包捕获各自的 `source`。

**② 过程记录面板**
- `TraceEvent.kt`：数据类，`kind ∈ think/tool/result/note/error`，`MAX_EVENTS=40`，think 的 detail 上限单独放宽到 2000 字
- `TraceView.kt`：折叠面板，默认 `visibility=GONE`（有记录才出现），标题「过程记录 · N」，点标题展开 ▸/▾，展开后有「复制」
- `AgentLoop.kt`：`turnTrace` 列表 + `trace()` 方法，同时送 UI 回调和落盘； THINK 只在「紧邻同类」时合并
- `LlmClient.chatStream` 新增 `onReasoning: (String) -> Unit` 增量回调（原来 reasoning 被吞掉，只发一次信号）
- 落盘走 `toHistoryJson()`（含 trace），发给模型走 `toJson()`（**不含 trace**）——两条路径严格分离，不污染模型上下文
- 切会话、杀进程重启后记录仍在

**③ 崩溃日志**
- `FeifeiApp.kt`（`: Application`）装 `Thread.setDefaultUncaughtExceptionHandler`（此前**完全没有 Application 类**）
- `CrashLog.kt`：落盘 `files/agent/crash.log`，环形（300 KB，保留 50 条），记录时间/版本/机型/Android 版本/异常类名+message/完整堆栈
- 写完交还系统默认 handler（否则进程带坏状态继续跑，用户看到的是卡死而非崩溃）
- `pendingRecover` 标记「上次崩着退的」，下次启动 3 秒后发通知提醒
- 设置页「查看崩溃日志」按钮：显示最近 5 条 + 复制全部 + 清空，按钮上标条数
- 静默 catch 的异常也上报（工具执行 / 定时任务 / 写会话 / 对话轮次）

## 现在有什么

**24 个工具**

| 工具 | 干什么 |
|---|---|
| `web_search` | Exa → degoog → 必应 三级降级 |
| `web_fetch` | 抓网页 / RSS |
| `weather` | wttr.in，不传城市用当前位置 |
| `email` / `email_send` | IMAP / SMTP（139 邮箱已验证收发） |
| `remember` | 写 memory.md |
| `notify` | 发通知（发不出去如实报错） |
| `save` | 写文件 |
| `share` | 分享到其他 App |
| `run_js` | WebView 跑 JS，带文件桥，能读写整个 filesDir |
| `web_app` | 写网页 + 本地服务器（端口 8848） |
| `self` | 自省：日志/提示词/接口/模型/摘要，12 个 action |
| `task` | 定时任务，多条、时间可调、支持一次性 |
| `netdisk` | 百度网盘 REST，token 30 天要手换 |
| `contact` / `phone` / `sms` | 通讯录 / 拨号 / 短信 |
| `location` / `map` | 位置 / 地图标点·路线，`showMap=true` 画路线图 |
| `notifications` | 读最近捕获的通知 + 验证码识别 |
| `file_write` | 写文本文件（私有目录） |
| `download` | 下载，`mode` = auto（默认）/ direct / system |
| `download_status` | 查 system 模式的任务进度 |
| `make_image` | 生成图片（`android.graphics` 绘制） |

> `download_native` 是内部实现类，**不单独注册进工具表**；对外只有 `download` + `download_status`。
> auto 分流规则：26 种大文件后缀 → system；响应头 `Content-Length` > 10 MB → system。
> API 26–28 降级用 `setDestinationInExternalFilesDir`，**不新增任何权限**。

**界面**

- 多会话（侧边抽屉），每会话独立存；v0.67 起支持多选批量删除
- 助手消息是卡片，用户消息右侧橙色气泡
- 代码块独立卡片：语言标签 + 复制按钮 + 横向滚动
- 正文链接可点，走内置浏览器
- 首页小飞 Canvas 绘制（渐变+阴影+状态机，目前只接待机）

**定时任务**：配置文件 `files/agent/tasks.json`；AlarmManager 精确闹钟 + WorkManager 执行，开机自动重排；支持一次性任务（跑完自动删）。

**会话与记忆**：每会话 `files/agent/sessions/{id}.json`，含消息 + 摘要 + 过程记录；历史超 20 条或 6000 字自动压成 300 字摘要，只留最近 8 条原文。

## 数据文件（都能手改，下一轮生效）

```
files/agent/
├── config.json        接口 + 模型清单 + 运行参数
├── system.md          系统提示词，删掉恢复默认
├── memory.md          长期记忆，一行一条
├── tasks.json         定时任务
├── custom_tools.json  自建 HTTP 工具
├── sessions/          每段对话一个 json
├── crash.log          崩溃日志（v0.71 新增）
└── log.jsonl          运行日志，环形，最多 300 条
```

`config.json` 核心结构：

```json
{
  "active": "cmecloud",
  "models": [{ "id": "Qwen3.5-27B", "note": "最稳…" }],
  "providers": [{ "id": "cmecloud", "baseUrl": "...", "apiKey": "...", "model": "..." }],
  "agent": { "maxTurns": 4, "backgroundTurns": 6, "maxToolCalls": 10 }
}
```

删掉 config.json 不会崩（会从 SharedPreferences 重建），但多套接口和自定义参数会丢。

## 已知问题

| 优先级 | 事项 |
|---|---|
| 高 | **几乎所有功能只编译过，没在真机跑过**：定时任务、web_app、run_js、通讯录/拨号/短信、通知读取、地图白名单（高德 JS key 需加 127.0.0.1/localhost） |
| 高 | v0.71 三个新功能（复制/过程记录/崩溃日志）也**未真机验证** |
| 中 | 崩溃日志只能在设置页看，没有导出成文件分享的入口 |
| 中 | 正文长按选中被「链接可点」取代（抢触摸事件，只能二选一） |
| 中 | 标题栏小飞 30dp 是否还认得出（原 126dp 画的，缩到 1/4 没在真机看过） |
| 低 | Exa 免费额度撑不住日用，多数搜索落到 degoog |
| 低 | 会话不能重命名、不能长按删单个 |
| 低 | DOCX / Excel 生成**没做**（用户明确要求先不实现） |

## 下一步（按性价比）

1. **真机逐项验证**（v0.71 三个新功能优先）。先修坏掉的，别急着加新功能。
   具体怎么测见 `DEVELOP.md` §9。
2. `make_pdf`（`android.graphics.pdf.PdfDocument`）—— 路线图第 5 步，唯一未做的本地工具。
3. 会话重命名 / 长按删单个（待用户决策）。
4. 语音输入（麦克风权限 + 识别服务）。

---

## 想立刻动手改代码？看 `DEVELOP.md`

里面有：**代码地图**（57 个文件各干什么）、**怎么加一个新工具**（完整模板 + 5 条铁律）、
**改界面的约定**（没有 RecyclerView，每条消息独立 StringBuilder）、
**自检清单**（构建 + 验签 + 查版本号三条命令）、**数据与配置**、**10 条踩坑表**。


## 不要做的事

- 不要引入 agent 框架。200 行循环够用，引框架只会让排障变难
- 不要把 API Key 硬编码进 APK，反解就能拿到
- 不要在没有真机验证的情况下连着改三个功能 —— 这个项目已经攒了一批「编译通过但没跑过」的代码
- 不要把这个 APK 发给别人。它没有任何权限隔离，配置里有明文 Key
- **不要每次都压缩记忆/打包压缩库**，开发为主

## 踩过的 Kotlin / Android 坑（省得再踩）

- **局部变量名会遮蔽 receiver 同名属性**：`apply { text = "x" }` 外层若有 `val text` → 编译报 `Val cannot be reassigned`，必须写 `this.text = "x"`
- `drawable` XML 的 `xmlns:android` 必须是 `http://schemas.android.com/apk/res/android`（写 `res/android` 报 lint 致命错 `NamespaceTypo`）
- `private val x: TextView` 在 init 里赋值不合法 → 用 `private lateinit var`
- lint `versionCode: Int is deprecated` → 加 `@Suppress("DEPRECATION")`
- lint `Unsafe use of a nullable receiver of type Any?` → `v.toString()` 前先判 `v == null || v == JSONObject.NULL`

---

最后更新：2026-10-23
当前版本：v0.72（versionCode 72）
源码目录：`feifei-v0.71/`
模型：移动云 MoMA，Base URL `https://zhenze-huhehaote.cmecloud.cn/v1`，`Qwen3.5-27B`
