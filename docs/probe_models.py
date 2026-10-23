import json, os, time, urllib.request, urllib.error

KEY = os.environ["K"]
BASE = "https://zhenze-huhehaote.cmecloud.cn/v1/chat/completions"

models = [
    "deepseek-v4.1-flash",
    "DeepSeek-V4-Flash",
    "glm-5.3-flash",
    "Qwen3.5-27B",
    "Qwen3.5-35B-A3B",
    "MiniMax-M2.5",
    "DeepSeek-V3.2",
    "qwen3.5-397b-a17b",
]

TOOLS = [{
    "type": "function",
    "function": {
        "name": "get_weather",
        "description": "查询某个城市当前天气",
        "parameters": {
            "type": "object",
            "properties": {"city": {"type": "string", "description": "城市名"}},
            "required": ["city"],
        },
    },
}]

def call(model):
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": "北京现在天气怎么样？用工具查。"}],
        "tools": TOOLS,
        "tool_choice": "auto",
        "temperature": 0.3,
    }
    req = urllib.request.Request(
        BASE,
        data=json.dumps(payload).encode(),
        headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"},
        method="POST",
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            data = json.loads(r.read())
        dt = time.time() - t0
        msg = data["choices"][0]["message"]
        tc = msg.get("tool_calls") or []
        if tc:
            res = "工具调用 OK -> " + tc[0]["function"]["name"] + " " + tc[0]["function"]["arguments"][:60]
        else:
            res = "无工具调用，直接回：" + (msg.get("content") or "")[:50].replace("\n", " ")
        return True, dt, res
    except urllib.error.HTTPError as e:
        body = e.read().decode()[:200].replace("\n", " ")
        return False, time.time() - t0, f"HTTP {e.code} {body}"
    except Exception as e:
        return False, time.time() - t0, f"ERR {type(e).__name__} {str(e)[:80]}"

for m in models:
    ok, dt, res = call(m)
    print(f"{'✓' if ok else '✗'} {m:<24} {dt:5.1f}s  {res}")
