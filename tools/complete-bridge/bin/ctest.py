#!/usr/bin/env python3
"""Smoke test /complete (token read from .env, never printed)."""
import json, sys, time, urllib.request
tok = next(l.split("=",1)[1].strip() for l in open("/workspace/muse-bridge/.env") if l.startswith("COMPLETE_TOKEN="))
url = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8776/complete"
for text in (sys.argv[1:2] or ["I'm running a little", "Thanks so much for", "Can you call me when"]):
    t0 = time.time()
    req = urllib.request.Request(url, data=json.dumps({"text": text}).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + tok, "ngrok-skip-browser-warning": "1"})
    r = json.loads(urllib.request.urlopen(req, timeout=30).read())
    print(json.dumps({"in": text, "out": r.get("completion"), "server_ms": r.get("ms"), "rt_ms": int((time.time()-t0)*1000)}))
