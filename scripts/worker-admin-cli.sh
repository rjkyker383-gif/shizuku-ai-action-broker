#!/data/data/com.termux/files/usr/bin/bash
# Broker CLI helpers for delegated admin (Termux / rish fallback)
PKG=com.rjkyker.shizukuai.broker
RISH="${RISH:-$HOME/rish}"

worker-admin-scopes() {
  "$RISH" -c "dumpsys device_policy | grep -A 20 Delegation" || true
  am broadcast -a com.rjkyker.shizukuai.broker.ADMIN_SCOPES -n "$PKG/.cli.AdminCliReceiver" 2>/dev/null || true
}

worker-admin-perm() {
  local p="$1" perm="$2" state="$3"
  am startservice -n "$PKG/.cli.AdminCliService" --es cmd perm --es package "$p" --es permission "$perm" --es state "$state"
}

worker-admin-suspend() {
  am startservice -n "$PKG/.cli.AdminCliService" --es cmd suspend --es package "$1" --ez suspended "$2"
}

worker-admin-block-uninstall() {
  am startservice -n "$PKG/.cli.AdminCliService" --es cmd block-uninstall --es package "$1" --ez blocked "$2"
}

worker-admin-enable-sys() {
  am startservice -n "$PKG/.cli.AdminCliService" --es cmd enable-sys --es package "$1"
}

case "${1:-}" in
  scopes) worker-admin-scopes ;;
  perm) shift; worker-admin-perm "$@" ;;
  suspend) shift; worker-admin-suspend "$@" ;;
  block-uninstall) shift; worker-admin-block-uninstall "$@" ;;
  enable-sys) shift; worker-admin-enable-sys "$@" ;;
  *) echo "usage: $0 scopes|perm|suspend|block-uninstall|enable-sys ..." ;;
esac
