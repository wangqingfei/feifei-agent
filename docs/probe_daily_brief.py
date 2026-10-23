import json, os, re, urllib.request, time
from xml.etree import ElementTree as ET

KEY = os.environ["K"]
BASE = "https://zhenze-huhehaote.cmecloud.cn/v1/chat/completions"
UA = "Mozilla/5.0 (Linux; Android 13) MiniAgent/0.2"

FEEDS = ["https://sspai.com/feed", "https://www.ithome.com/rss/"]

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read().decode("utf-8", "ignore")

def digest(xml, limit=10):
    root = ET.fromstring(xml.encode("utf-8"))
    out = []
    for item in root.iter("item"):
        t = (item.findtext("title") or "").strip()
        if not t:
            continue
        d = re.sub(r"<[^>]+>", "", item.findtext("description") or "")
        d = re.sub(r"\s+", " ", d).strip()
        l = (item.findtext("link") or "").strip()
        out.append(f"- {t}\n  {d[:160]}\n  {l}")
        if len(out) >= limit:
            break
    return "\n".join(out)

blocks = []
for f in FEEDS:
    try:
        blocks.append(f"【{f}】\n" + digest(fetch(f)))
    except Exception as e:
        blocks.append(f"【{f}】抓取失败：{e}")

material = "\n\n".join(blocks)[:6000]
print("素材长度:", len(material), "字\n")

prompt = (
    "下面是今天抓到的两批订阅内容。挑出 3 条最值得知道的，"
    "每条一句话说清是什么、为什么重要。不要开场白，不要总结，直接列三条。\n\n" + material
)

payload = {
    "model": "deepseek-v4.1-flash",
    "messages": [{"role": "user", "content": prompt}],
    "temperature": 0.4,
}
req = urllib.request.Request(
    BASE, data=json.dumps(payload).encode(),
    headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"}, method="POST")
t0 = time.time()
with urllib.request.urlopen(req, timeout=90) as r:
    data = json.loads(r.read())
print(f"耗时 {time.time()-t0:.1f}s\n")
print("=== 明早你会收到的通知 ===")
print(data["choices"][0]["message"]["content"])
