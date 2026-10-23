import json, urllib.request, time

URL = "https://mcp.exa.ai/mcp"
UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
BASE_H = {"Content-Type": "application/json",
          "Accept": "application/json, text/event-stream",
          "User-Agent": UA}

def post(payload, sid=None):
    h = dict(BASE_H)
    if sid:
        h["mcp-session-id"] = sid
    req = urllib.request.Request(URL, data=json.dumps(payload).encode(), headers=h, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, dict(r.headers), r.read().decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "ignore")

def sse_json(body):
    for line in body.splitlines():
        if line.startswith("data:"):
            try:
                return json.loads(line[5:].strip())
            except Exception:
                pass
    return None

st, hd, _ = post({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                  "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                             "clientInfo": {"name": "mini-agent", "version": "0.4"}}})
sid = hd.get("mcp-session-id")
post({"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)

st, _, body = post({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}, sid)
j = sse_json(body)
print("== tools ==")
names = []
for t in (j or {}).get("result", {}).get("tools", []):
    names.append(t["name"])
    req = (t.get("inputSchema") or {}).get("required", [])
    print(" -", t["name"], "| required:", req)
    print("   ", (t.get("description") or "")[:90])

# 调第一个搜索类工具
target = next((n for n in names if "search" in n.lower()), None)
if target:
    print("\n== call:", target, "==")
    t0 = time.time()
    st, _, body = post({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                        "params": {"name": target, "arguments": {"query": "护士 夜班 健康", "numResults": 3}}}, sid)
    print("HTTP", st, f"{time.time()-t0:.1f}s")
    j = sse_json(body)
    res = (j or {}).get("result", {})
    content = res.get("content", [])
    for c in content[:2]:
        txt = c.get("text", "")
        print("----", len(txt), "chars ----")
        print(txt[:900])
    if not content:
        print(body[:600])
