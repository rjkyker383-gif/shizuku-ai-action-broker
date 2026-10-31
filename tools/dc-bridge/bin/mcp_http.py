#!/usr/bin/env python3
"""Minimal Streamable-HTTP MCP client: mcp_http.py URL tools | call NAME 'JSON'."""
import json, sys, urllib.request
url = sys.argv[1]
H = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
sid = None
def rpc(method, params=None, id_=1):
    global sid
    body = {"jsonrpc": "2.0", "method": method, **({"params": params} if params is not None else {}), **({"id": id_} if id_ else {})}
    h = dict(H); 
    if sid: h["Mcp-Session-Id"] = sid
    r = urllib.request.urlopen(urllib.request.Request(url, data=json.dumps(body).encode(), headers=h), timeout=120)
    sid = r.headers.get("Mcp-Session-Id") or sid
    raw = r.read().decode()
    if not id_: return None
    for line in raw.splitlines():
        if line.startswith("data:"): raw = line[5:]
    return json.loads(raw) if raw.strip() else None
rpc("initialize", {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "box-cli", "version": "1"}})
rpc("notifications/initialized", None, None)
if sys.argv[2] == "tools":
    print("\n".join(t["name"] for t in rpc("tools/list", {}, 2)["result"]["tools"]))
else:
    res = rpc("tools/call", {"name": sys.argv[3], "arguments": json.loads(sys.argv[4] if len(sys.argv) > 4 else "{}")}, 3)
    for c in res.get("result", {}).get("content", []): print(c.get("text", ""))
    if "error" in res: print(json.dumps(res["error"]))
