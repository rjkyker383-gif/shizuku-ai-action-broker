#!/data/data/com.termux/files/usr/bin/bash
# Shizuku AI Broker — Termux client menu
# Broker HTTP listener: 127.0.0.1:17999 (localhost ONLY on the device where broker runs)
#
# If broker is on THIS phone: leave BROKER_HOST=127.0.0.1
# If broker is on TC22 (Tailscale tc22-1 / 100.95.242.123): open a tunnel first, e.g.
#   adb connect 100.95.242.123:5555
#   adb -s 100.95.242.123:5555 forward tcp:17999 tcp:17999
# then keep BROKER_HOST=127.0.0.1 (Termux hits the forwarded local port)
# Or: ssh -N -L 17999:127.0.0.1:17999 user@100.95.242.123

set -u
BROKER_HOST="${BROKER_HOST:-127.0.0.1}"
BROKER_PORT="${BROKER_PORT:-17999}"
BASE="http://${BROKER_HOST}:${BROKER_PORT}"

need() { command -v "$1" >/dev/null 2>&1 || pkg install -y "$1" >/dev/null 2>&1; }
need curl
need jq || true

pretty() {
  if command -v jq >/dev/null 2>&1; then jq -C . 2>/dev/null || cat
  else cat
  fi
}

get() {
  echo "→ GET $1"
  curl -sS --max-time 20 "$BASE$1" | pretty
  echo
}

post() {
  local json="$1"
  echo "→ POST /action  $json"
  curl -sS --max-time 30 -X POST "$BASE/action" \
    -H 'Content-Type: application/json' \
    -d "$json" | pretty
  echo
}

pause() { read -r -p "Enter…" _; }

# ── Connection helpers ──────────────────────────────────────────
menu_conn() {
  while true; do
    clear
    echo "════════ CONNECTION / HEALTH ════════"
    echo " Target: $BASE"
    echo " 1) Health check          GET /health"
    echo " 2) Ping + show latency"
    echo " 3) Set BROKER_HOST (current: $BROKER_HOST)"
    echo " 4) Set BROKER_PORT (current: $BROKER_PORT)"
    echo " 5) Print adb-forward recipe (TC22)"
    echo " 6) Test TCP port open"
    echo " 0) Back"
    read -r -p "> " c
    case "$c" in
      1) get /health; pause ;;
      2)
        start=$(date +%s%3N 2>/dev/null || echo 0)
        out=$(curl -sS --max-time 10 -w '\nHTTP:%{http_code} TIME:%{time_total}' "$BASE/health" || true)
        echo "$out" | pretty
        echo
        pause
        ;;
      3) read -r -p "Host: " BROKER_HOST; BASE="http://${BROKER_HOST}:${BROKER_PORT}" ;;
      4) read -r -p "Port: " BROKER_PORT; BASE="http://${BROKER_HOST}:${BROKER_PORT}" ;;
      5)
        cat <<'TIP'
# From Termux (needs android-tools / adb) toward TC22 on Tailscale:
pkg install android-tools
adb connect 100.95.242.123:5555
adb -s 100.95.242.123:5555 forward tcp:17999 tcp:17999
export BROKER_HOST=127.0.0.1 BROKER_PORT=17999
# Then re-run this menu. Broker itself only binds 127.0.0.1 on the TC22.
TIP
        pause
        ;;
      6)
        if command -v nc >/dev/null 2>&1; then
          nc -zv "$BROKER_HOST" "$BROKER_PORT" 2>&1 || true
        else
          curl -sS -o /dev/null -w 'http_code=%{http_code}\n' --max-time 5 "$BASE/health" || echo "unreachable"
        fi
        pause
        ;;
      0) return ;;
    esac
  done
}

# ── UI control ──────────────────────────────────────────────────
menu_ui() {
  while true; do
    clear
    echo "════════ UI / GESTURES ════════"
    echo " 1) UI dump (GET /ui)"
    echo " 2) UI dump via action (maxNodes)"
    echo " 3) Tap by screen x,y"
    echo " 4) Tap by visible text"
    echo " 5) Swipe x1,y1 → x2,y2"
    echo " 6) Type text into focus"
    echo " 7) Key: BACK"
    echo " 8) Key: HOME"
    echo " 9) Key: custom keycode"
    echo " 0) Back"
    read -r -p "> " c
    case "$c" in
      1) get /ui; pause ;;
      2)
        read -r -p "maxNodes [200]: " n; n=${n:-200}
        post "{\"op\":\"ui_dump\",\"maxNodes\":$n}"
        pause
        ;;
      3)
        read -r -p "x: " x; read -r -p "y: " y
        post "{\"op\":\"tap\",\"x\":$x,\"y\":$y}"
        pause
        ;;
      4)
        read -r -p "text: " t
        post "{\"op\":\"tap\",\"text\":$(printf '%s' "$t" | jq -Rs . 2>/dev/null || echo "\"$t\"")}"
        pause
        ;;
      5)
        read -r -p "x1 y1 x2 y2 durationMs: " x1 y1 x2 y2 d
        d=${d:-300}
        post "{\"op\":\"swipe\",\"x1\":$x1,\"y1\":$y1,\"x2\":$x2,\"y2\":$y2,\"durationMs\":$d}"
        pause
        ;;
      6)
        read -r -p "text: " t
        # JSON-escape via jq if present
        if command -v jq >/dev/null 2>&1; then
          post "$(jq -nc --arg t "$t" '{op:"text",text:$t}')"
        else
          post "{\"op\":\"text\",\"text\":\"$t\"}"
        fi
        pause
        ;;
      7) post '{"op":"key","key":"back"}'; pause ;;
      8) post '{"op":"key","key":"home"}'; pause ;;
      9)
        read -r -p "keycode number: " k
        post "{\"op\":\"key\",\"keycode\":$k}"
        pause
        ;;
      0) return ;;
    esac
  done
}

# ── Shell ───────────────────────────────────────────────────────
menu_shell() {
  while true; do
    clear
    echo "════════ SHELL (via Shizuku on broker device) ════════"
    echo " 1) Custom command"
    echo " 2) whoami / id"
    echo " 3) dumpsys window | head"
    echo " 4) pm list packages -3 (user apps)"
    echo " 5) settings get global adb_wifi_enabled"
    echo " 6) Re-enable wireless debug settings (secure settings)"
    echo " 0) Back"
    read -r -p "> " c
    case "$c" in
      1)
        read -r -p "cmd: " cmd
        if command -v jq >/dev/null 2>&1; then
          post "$(jq -nc --arg c "$cmd" '{op:"shell",cmd:$c}')"
        else
          post "{\"op\":\"shell\",\"cmd\":\"$cmd\"}"
        fi
        pause
        ;;
      2) post '{"op":"shell","cmd":"id; whoami; getprop ro.product.model"}'; pause ;;
      3) post '{"op":"shell","cmd":"dumpsys window windows 2>/dev/null | head -n 40"}'; pause ;;
      4) post '{"op":"shell","cmd":"pm list packages -3 | head -n 80"}'; pause ;;
      5) post '{"op":"shell","cmd":"settings get global adb_wifi_enabled; settings get global adb_enabled"}'; pause ;;
      6) post '{"op":"shell","cmd":"settings put global adb_enabled 1; settings put global development_settings_enabled 1; settings put global adb_wifi_enabled 1; echo done"}'; pause ;;
      0) return ;;
    esac
  done
}

# ── Launch ──────────────────────────────────────────────────────
menu_launch() {
  while true; do
    clear
    echo "════════ LAUNCH APPS / INTENTS ════════"
    echo " 1) Launch by package"
    echo " 2) Launch Shizuku"
    echo " 3) Launch Dhizuku"
    echo " 4) Launch broker itself"
    echo " 5) Launch DataWedge"
    echo " 6) Custom intent action (+ optional data URI)"
    echo " 0) Back"
    read -r -p "> " c
    case "$c" in
      1)
        read -r -p "package: " p
        post "{\"op\":\"launch\",\"package\":\"$p\"}"
        pause
        ;;
      2) post '{"op":"launch","package":"moe.shizuku.manager"}'; pause ;;
      3) post '{"op":"launch","package":"com.rosan.dhizuku"}'; pause ;;
      4) post '{"op":"launch","package":"com.rjkyker.shizukuai.broker"}'; pause ;;
      5) post '{"op":"launch","package":"com.symbol.datawedge"}'; pause ;;
      6)
        read -r -p "action: " a
        read -r -p "data URI (optional): " d
        if command -v jq >/dev/null 2>&1; then
          post "$(jq -nc --arg a "$a" --arg d "$d" '{op:"launch",action:$a,data:$d}')"
        else
          post "{\"op\":\"launch\",\"action\":\"$a\",\"data\":\"$d\"}"
        fi
        pause
        ;;
      0) return ;;
    esac
  done
}

# ── DataWedge (Zebra / TC22) ────────────────────────────────────
menu_dw() {
  while true; do
    clear
    echo "════════ DATAWEDGE (Zebra) ════════"
    echo " 1) Live query all          GET /datawedge/query?q=all"
    echo " 2) Persisted results       GET /datawedge"
    echo " 3) Status"
    echo " 4) Version"
    echo " 5) Profiles list"
    echo " 6) Active profile"
    echo " 7) Soft scan START"
    echo " 8) Soft scan STOP"
    echo " 9) Switch profile"
    echo "10) Create profile"
    echo "11) Set minimal broadcast config"
    echo "12) Last live snapshot"
    echo " 0) Back"
    read -r -p "> " c
    case "$c" in
      1) get '/datawedge/query?q=all'; pause ;;
      2) get /datawedge; pause ;;
      3) post '{"op":"datawedge_status"}'; pause ;;
      4) post '{"op":"datawedge_version"}'; pause ;;
      5) post '{"op":"datawedge_profiles"}'; pause ;;
      6) post '{"op":"datawedge_active_profile"}'; pause ;;
      7) post '{"op":"datawedge_soft_scan","mode":"start"}'; pause ;;
      8) post '{"op":"datawedge_soft_scan","mode":"stop"}'; pause ;;
      9)
        read -r -p "profile name: " n
        post "{\"op\":\"datawedge_switch_profile\",\"profile\":\"$n\"}"
        pause
        ;;
      10)
        read -r -p "profile name [ShizukuAiBroker]: " n; n=${n:-ShizukuAiBroker}
        post "{\"op\":\"datawedge_create_profile\",\"profile\":\"$n\"}"
        pause
        ;;
      11)
        read -r -p "profile name [ShizukuAiBroker]: " n; n=${n:-ShizukuAiBroker}
        post "{\"op\":\"datawedge_set_config\",\"profile\":\"$n\"}"
        pause
        ;;
      12) post '{"op":"datawedge_last"}'; pause ;;
      0) return ;;
    esac
  done
}

# ── Dhizuku / device owner ──────────────────────────────────────
menu_dhizuku() {
  while true; do
    clear
    echo "════════ DHIZUKU / DEVICE OWNER ════════"
    echo " 1) Status"
    echo " 2) Connect / request grant UI"
    echo " 3) Get scopes"
    echo " 4) Set scopes (JSON array)"
    echo " 5) Add restriction (needs confirm)"
    echo " 6) Clear restriction (needs confirm)"
    echo " 7) Hide app (needs confirm)"
    echo " 8) Suspend app (needs confirm)"
    echo " 9) Set permission grant state"
    echo "10) Delegated admin: list scopes"
    echo "11) Delegated admin: set perm"
    echo "12) Delegated admin: suspend pkg"
    echo " 0) Back"
    read -r -p "> " c
    case "$c" in
      1) post '{"op":"dhizuku_status"}'; pause ;;
      2) post '{"op":"dhizuku_connect"}'; pause ;;
      3) post '{"op":"dhizuku_scopes"}'; pause ;;
      4)
        read -r -p 'scopes JSON array e.g. ["delegation-package-access"]: ' s
        post "{\"op\":\"dhizuku_set_scopes\",\"scopes\":$s,\"confirm\":true}"
        pause
        ;;
      5)
        read -r -p "restriction key: " r
        post "{\"op\":\"dhizuku_add_restriction\",\"restriction\":\"$r\",\"confirm\":true}"
        pause
        ;;
      6)
        read -r -p "restriction key: " r
        post "{\"op\":\"dhizuku_clear_restriction\",\"restriction\":\"$r\",\"confirm\":true}"
        pause
        ;;
      7)
        read -r -p "package: " p
        post "{\"op\":\"dhizuku_hide_app\",\"package\":\"$p\",\"hidden\":true,\"confirm\":true}"
        pause
        ;;
      8)
        read -r -p "package: " p
        post "{\"op\":\"dhizuku_suspend_app\",\"package\":\"$p\",\"suspended\":true,\"confirm\":true}"
        pause
        ;;
      9)
        read -r -p "package: " p
        read -r -p "permission: " perm
        read -r -p "state [default|granted|denied]: " st; st=${st:-default}
        post "{\"op\":\"dhizuku_perm\",\"package\":\"$p\",\"permission\":\"$perm\",\"state\":\"$st\",\"confirm\":true}"
        pause
        ;;
      10) post '{"op":"admin_scopes"}'; pause ;;
      11)
        read -r -p "package: " p
        read -r -p "permission: " perm
        read -r -p "state [default|granted|denied]: " st; st=${st:-default}
        post "{\"op\":\"admin_perm\",\"package\":\"$p\",\"permission\":\"$perm\",\"state\":\"$st\"}"
        pause
        ;;
      12)
        read -r -p "package: " p
        read -r -p "suspended true/false [true]: " s; s=${s:-true}
        post "{\"op\":\"admin_suspend\",\"package\":\"$p\",\"suspended\":$s}"
        pause
        ;;
      0) return ;;
    esac
  done
}

# ── Advanced / raw ──────────────────────────────────────────────
menu_adv() {
  while true; do
    clear
    echo "════════ ADVANCED / RAW JSON ════════"
    echo " 1) Paste raw POST /action JSON"
    echo " 2) Curl one-liner templates"
    echo " 3) Watch health every 2s (Ctrl+C stop)"
    echo " 4) Dump allow-listed ops cheat sheet"
    echo " 0) Back"
    read -r -p "> " c
    case "$c" in
      1)
        echo "End JSON with a line containing only END"
        json=""
        while IFS= read -r line; do
          [[ "$line" == END ]] && break
          json+="$line"
        done
        post "$json"
        pause
        ;;
      2)
        cat <<TIP
# Health
curl -s $BASE/health | jq .

# UI dump
curl -s $BASE/ui | jq .

# Tap
curl -s -X POST $BASE/action -H 'Content-Type: application/json' \\
  -d '{"op":"tap","x":540,"y":1200}' | jq .

# Shell
curl -s -X POST $BASE/action -H 'Content-Type: application/json' \\
  -d '{"op":"shell","cmd":"getprop ro.build.version.release"}' | jq .

# DataWedge live
curl -s '$BASE/datawedge/query?q=all' | jq .
TIP
        pause
        ;;
      3)
        while true; do
          date '+%H:%M:%S'
          curl -sS --max-time 5 "$BASE/health" | pretty
          sleep 2
        done
        ;;
      4)
        cat <<'SHEET'
GET  /health
GET  /ui  |  /uidump
GET  /datawedge
GET  /datawedge/query?q=all|status|version|profiles|active_profile
POST /action  body: {"op":"..."}

UI:     tap | swipe | text | key | ui_dump
Shell:  shell   {"cmd":"..."}
Launch: launch  {"package":"..."} or {"action":"...","data":"..."}
DW:     datawedge_status|version|profiles|active_profile|soft_scan|
        switch_profile|create_profile|set_config|last|query|results
Admin:  admin_scopes | admin_perm | admin_suspend
Dhizuku:dhizuku_status|connect|scopes|set_scopes|
        add_restriction|clear_restriction|hide_app|suspend_app|perm
        (HIGH-risk ops need "confirm": true)
SHEET
        pause
        ;;
      0) return ;;
    esac
  done
}

# ── Main ────────────────────────────────────────────────────────
while true; do
  clear
  echo "╔══════════════════════════════════════════╗"
  echo "║   Shizuku AI Broker — Termux Console     ║"
  echo "╚══════════════════════════════════════════╝"
  echo " Target: $BASE"
  echo
  echo " 1) Connection / Health"
  echo " 2) UI / Gestures"
  echo " 3) Shell (Shizuku)"
  echo " 4) Launch apps / intents"
  echo " 5) DataWedge (Zebra / TC22)"
  echo " 6) Dhizuku / Device Owner / Admin"
  echo " 7) Advanced / raw JSON / cheat sheet"
  echo " 8) Quick health (shortcut)"
  echo " q) Quit"
  read -r -p "> " m
  case "$m" in
    1) menu_conn ;;
    2) menu_ui ;;
    3) menu_shell ;;
    4) menu_launch ;;
    5) menu_dw ;;
    6) menu_dhizuku ;;
    7) menu_adv ;;
    8) get /health; pause ;;
    q|Q) exit 0 ;;
  esac
done
