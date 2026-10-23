# 探针脚本

这些不是 App 的一部分，是用来验证外部服务能不能用的临时脚本。
换环境或换 API Key 后，重跑一遍就知道哪个通道还活着。

运行前设置 Key（移动云的控制台里拿）：

```bash
export K='你的移动云APIKey'
```

| 脚本 | 用途 | 会花多少额度 |
|---|---|---|
| `probe_models.py` | 逐个模型测「能否对话 + 是否支持工具调用」，输出延迟 | 8 次请求 |
| `probe_exa_mcp.py` | 走 MCP 握手列出 Exa 的工具，并实调一次搜索 | 1 次搜索 |
| `probe_daily_brief.py` | 模拟每日简报：抓 sspai + IT之家，让模型挑 3 条 | 1 次长上下文请求 |

注意：

- `probe_models.py` 里的 `MODELS` 列表是 2026-10 的实测结果，服务商上下架会变，以脚本输出为准
- Exa 免费 MCP 有频率限制，连续跑会拿到 `You've hit Exa's free MCP rate limit`，这是正常现象
- 所有脚本都直连 `zhenze-huhehaote.cmecloud.cn`，如果换资源池要改 `URL`
