# 飞飞 · 手机端极简智能体

[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-blue)](https://kotlinlang.org)
[![Size](https://img.shields.io/badge/APK-2.8%20MB-lightgrey)](https://github.com)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

一个装在自己手机里的 AI 智能体。**不是一个聊天 App，是一个能动手的助手**——它能读短信、查通讯录、打电话、看通知、定位、画地图、下载文件、发邮件、跑代码。

> **一句话**：自己写的 ReAct 单循环 + 24 个工具，纯 Kotlin，2.8 MB，跑在 Android 8.0+ 的手机上。

---

## 它能做什么

**24 个工具，覆盖手机的大部分能力：**

| 类别 | 工具 | 说明 |
|---|---|---|
| 信息获取 | `web_search` `web_fetch` `weather` | 搜索三级降级 / 抓网页 RSS / 天气 |
| 通信 | `sms` `contact` `phone` `email` `email_send` `notifications` | 读短信 / 通讯录 / 拨号 / 收发邮件 / 读通知与验证码 |
| 位置 | `location` `map` | 实时定位 + 地址解析 / 标点、地名转坐标、驾车步行路线 |
| 文件 | `file_write` `download` `download_status` `share` | 写文件 / 下载（双引擎）/ 分享到其他 App |
| 计算 | `run_js` `web_app` | WebView 跑 JS（带文件桥）/ 写网页 + 本地服务器（8848 端口） |
| 自动化 | `task` `notify` `remember` `self` | 定时任务 / 发通知 / 长期记忆 / 自省与诊断 |
| 其他 | `netdisk` `make_image` `save` `web_app` | 百度网盘 / 生成图片 / 存文件 |

模型看到的是一张工具表，自己决定调哪个、传什么参数，失败会自己改参数重试——这是 ReAct 循环的意义。

**定时任务**：「每天早上 8 点给我一份 AI 新闻」→ 自动记进 `tasks.json`，用 AlarmManager 精确闹钟触发，WorkManager 执行，开机自动重排。

**过程可见**：每一轮对话下方有一个默认收起的「过程记录」面板，展开能看到它这一轮**思考了什么、调了什么工具、工具返回了什么**。不是黑盒。

---

## 极简体现在哪

| | |
|---|---|
| **APK 2.8 MB** | 没有 Electron，没有内嵌浏览器引擎（用系统 WebView） |
| **7 个依赖** | OkHttp、Jsoup、WorkManager、AndroidX Core/DrawerLayout、JavaMail×2 |
| **200 行循环** | 不引任何 agent 框架。引框架只会让排障变难 |
| **零统计、零广告 SDK** | 没有埋点，没有崩溃上报到第三方，没有数据分析 |
| **没有 RecyclerView** | 聊天界面是 ScrollView + LinearLayout 手工建 View。消息量小，够用 |
| **自建 JSON 解析** | 不引 Gson / Moshi |

---

## 隐私与安全

这是**自用工具**，隐私设计从一开始就是第一位的。具体措施，不喊口号：

### 数据存在哪

全部在 **App 私有目录** `files/agent/`（Android 沙箱，其他 App 读不到，不用 root 也拿不到）：

```
config.json        模型接口配置（含 API Key）
system.md          系统提示词
memory.md          长期记忆
tasks.json         定时任务
sessions/*.json    每段对话
crash.log          崩溃日志
log.jsonl          运行日志
```

**没有任何数据存在云端**。要备份就自己把目录拷出来。

### 凭据处理

- **API Key 不硬编码进 APK**。源码里只有默认 baseUrl，Key 留空，首次使用在设置页填。
  硬编码的 Key 反解 APK 就能拿到——这是很多开源 AI 客户端的通病。
- Key 只存在手机的 `config.json` 和 SharedPreferences 里，换手机要重填。

### 敏感信息脱敏

- 工具实现里有 `sensitive` 开关。电话、短信正文、通讯录这类结果置为 `true` 后，
  **运行日志里只记长度不记内容**（`log.jsonl` 是明文文件，模型自己也能读它，必须防）。
- 安全默认一律往保守方向靠：拨号默认只填拨号盘不直接拨出；发短信默认关闭。

### 权限最小化

- **按需申请**，不给就降级不崩（`AppPermissions` 统一处理）。
- 下载在 Android 8–9 上降级用 `setDestinationInExternalFilesDir`，**省掉存储权限**。
- 14 个权限每一个都有明确用途，没有"顺手申请"的。

### 网络请求可审计

出网的只有两类：
1. **你配置的模型接口**（默认是移动云 MoMA，OpenAI 兼容）
2. **工具自己的目标站点**（搜索、天气、网盘、邮件服务器等）

没有第三个。7 个依赖里没有一个会偷偷上报。

### 崩溃日志本地化

异常写进 `crash.log` 存在本机，**不上报任何服务器**。设置页可以直接看、复制、清空。

---

## ⚠️ 必须知道的边界

诚实说明这个项目做不到什么：

1. **对话内容会发给模型服务商。** 这是智能体的本质，无法避免。用哪个模型由你配置，
   数据就到哪家。对隐私极度敏感的话，把 baseUrl 指向自己部署的模型（兼容 OpenAI 接口即可）。
2. **这是一个人的自用工具，没有做多用户隔离。** 配置里有明文 Key，不要装给别人用。
3. **大部分功能只编译过，没在真机跑过。** v0.35 时期做过一轮 12 项实测，之后新增的
   （定时任务、通讯录、地图、二维码、下载、v0.71 三件套）都还没验证。
   已知问题见 `START-HERE.md`。

---

## 快速开始

### 直接用

下载 Release 里的 `MiniAgent-0.71.apk`，装到 Android 8.0+ 手机上。
设置页填 baseUrl / apiKey / model → 点「测试」→ 开始用。

### 自己构建

```bash
export JAVA_HOME=<你的 JDK 17+ 路径>
export ANDROID_HOME=/opt/android-sdk
git clone https://github.com/<你的账号>/feifei.git
cd feifei
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

需要：JDK 17+、Android SDK（platform 34 + build-tools 34.0.0）。Gradle 用 wrapper，已配华为镜像（国内可直连）。

**签名**：仓库**不含**签名钥匙。构建时若检测到本地 `app/personal.jks`（口令经
`gradle.properties` 的 `FEIFEI_STORE_PASS`/`FEIFEI_KEY_PASS` 或同名环境变量传入）则用它签名；
没有则产出 unsigned APK，自己 `apksigner sign` 即可。
> 覆盖安装必须用同一把钥匙，换了系统会当成另一个 App、数据全丢。

---

## 文档

| 文件 | 内容 |
|---|---|
| [`START-HERE.md`](START-HERE.md) | 这**是什么**：功能清单、已知问题、下一步 |
| [`DEVELOP.md`](DEVELOP.md) | 怎么**改**：57 个文件的代码地图、怎么加工具、自检命令、踩坑表 |
| [`HISTORY.md`](HISTORY.md) | 为什么**这么做**：提示词实测踩的坑、工具描述四条发现、做过又撤掉的、完整版本演进 |

---

## 架构一瞥

```
用户输入
   ↓
AgentLoop（ReAct 单循环，最多 4 轮 / 工具调用上限 10 次）
   ↓
LlmClient（SSE 流式）→ 模型决定：直接回答 还是 调工具
   ↓                        ↓
  回答                 ToolRegistry.run()（永不抛异常，失败转文本回给模型）
                            ↓
                       24 个工具 → 结果回灌 → 下一轮
   ↓
TraceEvent 全程记录 → TraceView 折叠面板（用户可展开查看）
```

工具跑在 3 线程的线程池上。每轮的过程记录会落盘，但**发给模型时不含记录**——
两条路径严格分离，不污染上下文。

---

## 演进

0.1 基础对话 → 0.3 流式 → 0.17 JS 执行 → 0.23 多会话 → 0.37 定时任务 →
0.41 小飞形象 → 0.52 循环加固 → 0.58 地图 → 0.69 二维码 → 0.70 下载统一 →
**0.71 复制 + 过程记录 + 崩溃日志**

完整演进表（含每个版本的取舍理由）见 [`HISTORY.md`](HISTORY.md)。

---

## License

MIT

---

**最后**：这是一个人给自己做的工具，不是产品。它很粗糙，很多功能没验证过，
但每一行都是为了解决自己的真实问题写的。如果你也想要一个能动手的手机智能体，欢迎拿去改。
