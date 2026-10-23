# 参与飞飞

感谢你愿意给「飞飞」添砖加瓦。这是一个**个人自用工具**，所以协作方式和成熟开源项目不太一样，先说清楚，免得互相浪费时间。

## 最适合你的参与方式

| 你的情况 | 建议 |
|---|---|
| **自己也在用**（装了 APK，遇到 bug） | 提 Issue，按模板填信息，这是最有价值的贡献 |
| **想给自己做一个类似的** | 直接 fork 拿去改。MIT 许可，不用问我 |
| **想改某个功能** | 先开 Issue 讨论，避免你写完我发现方向不对 |
| **只想反馈体验** | Issue 或 Discussion 都行 |

## 提交 Issue 前

1. **先搜一下**有没有人提过同样的（Issue 列表右侧搜关键词）
2. **确认版本**：设置页或 Release 页看版本号（如 v0.72）
3. **崩溃类问题**：App 内「设置 → 查看崩溃日志 → 复制全部」，把堆栈贴进 Issue —— 这比任何描述都有用
4. **过程记录**：对话下方「过程记录」面板展开后复制内容，能看出模型当时做了什么

## 提 PR 的话

```bash
git clone https://github.com/<你的账号>/feifei.git
cd feifei
git checkout -b fix-something
# 改代码
./gradlew assembleRelease    # 必须构建通过
git commit -m "说清改了什么、为什么"
```

**PR 硬性要求**（会被打回的）：

1. `./gradlew assembleRelease` 通过，无 lint 致命错误
2. 不引入 agent 框架、不新增不必要的依赖
3. 不硬编码任何 API Key
4. 新增工具必须在 `AgentFactory.registry()` 注册，且 `execute()` 不抛异常
5. 敏感数据（电话/短信/凭据）走 `sensitive = true`

## 一个坦白

**这个项目大部分功能只编译过，没在真机跑过。** 已知问题清单见 `START-HERE.md`。
所以如果你发现某个功能完全不能用，很可能不是你的问题——请提 Issue，那是在帮我。

## 架构背景

改代码前读 `DEVELOP.md`（代码地图 + 加工具模板 + 踩坑表），
改提示词或工具描述前读 `HISTORY.md`（那里有实测踩出来的坑，能帮你避开）。

给 AI 助手的最小指引在 `AGENTS.md`。
