#!/usr/bin/env python3
"""Token-protected sentence-completion endpoint for the broker (type-anywhere), tc22-type and Desktop Commander.
POST /complete {"text": "...", "max_words": 8}  ->  {"ok":true,"completion":" next words","model":..,"ms":..}
Auth: Authorization: Bearer $COMPLETE_TOKEN. Logs lengths only (never text, never the token)."""
import hmac, json, os, re, sys, time, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ENV = "/workspace/muse-bridge/.env"
LLAMA = "http://127.0.0.1:8792"
MODEL = "qwen2.5-0.5b-instruct-q4_k_m"
LOG = "/workspace/complete-bridge/logs/complete.log"

def token():
    for line in open(ENV):
        if line.startswith("COMPLETE_TOKEN="):
            return line.split("=", 1)[1].strip()
    return None

TOKEN = token()
if not TOKEN:
    sys.exit("COMPLETE_TOKEN missing in .env")

def log(msg):
    with open(LOG, "a") as f:
        f.write(time.strftime("%Y-%m-%dT%H:%M:%S%z ") + msg + "\n")

def clean(before, raw, max_words):
    s = raw.replace("\r", "").split("\n")[0]
    m = re.search(r"[.!?](\s|$)", s)
    if m: s = s[: m.start() + 1]
    parts = re.split(r"(?<=\S)(?=\s)", s)
    if len([p for p in parts if p.strip()]) > max_words: s = "".join(parts[:max_words])
    if before and before[-1].isspace(): s = s.lstrip()
    return s.rstrip()

def complete(text, max_words):
    prompt = "A casual message the user is typing on their phone:\n\n" + text[-1500:]
    body = json.dumps({"prompt": prompt, "n_predict": 24, "temperature": 0.3, "top_k": 40, "top_p": 0.9,
                       "stop": ["\n"], "cache_prompt": True, "repeat_penalty": 1.1}).encode()
    req = urllib.request.Request(LLAMA + "/completion", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        out = json.loads(r.read())
    return clean(text, out.get("content", ""), max_words)

class H(BaseHTTPRequestHandler):
    server_version = "complete/1"
    def log_message(self, *a): pass
    def send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        p = self.path.rstrip("/").split("?")[0]
        if p.endswith("/health"):
            try:
                with urllib.request.urlopen(LLAMA + "/health", timeout=3) as r: up = r.status == 200
            except Exception: up = False
            return self.send(200, {"ok": True, "llama": up, "model": MODEL})
        self.send(404, {"ok": False, "error": "not found"})
    def do_POST(self):
        if not self.path.rstrip("/").split("?")[0].endswith("/complete"):
            return self.send(404, {"ok": False, "error": "not found"})
        auth = self.headers.get("Authorization", "")
        if not (auth.startswith("Bearer ") and hmac.compare_digest(auth[7:].strip(), TOKEN)):
            log("401 unauthorized")
            return self.send(401, {"ok": False, "error": "unauthorized"})
        try:
            n = min(int(self.headers.get("Content-Length", "0")), 16000)
            j = json.loads(self.rfile.read(n) or b"{}")
            text = str(j.get("text", ""))[-2000:]
            mw = max(1, min(int(j.get("max_words", 8)), 16))
            if not text.strip():
                return self.send(400, {"ok": False, "error": "empty text"})
            t0 = time.time()
            c = complete(text, mw)
            ms = int((time.time() - t0) * 1000)
            log(f"200 in_len={len(text)} out_len={len(c)} ms={ms}")
            self.send(200, {"ok": bool(c), "completion": c, "model": MODEL, "ms": ms, **({} if c else {"error": "empty completion"})})
        except Exception as e:
            log(f"500 {type(e).__name__}")
            self.send(500, {"ok": False, "error": type(e).__name__})

if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8776), H).serve_forever()
