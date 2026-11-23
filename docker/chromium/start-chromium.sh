#!/usr/bin/env bash
# 容器入口：启动 Xvfb（有头模式）→ Chromium → CDP relay。
# 所有 Chromium 参数由控制面通过 CMD 传入（$@ 原样透传给 chrome）。
set -euo pipefail

CHROME_BIN="${RB_CHROME_BIN:-/ms-playwright/chromium-1148/chrome-linux/chrome}"
CDP_PORT="${RB_CDP_PORT:-9222}"
RELAY_PORT="${RB_CDP_RELAY_PORT:-9223}"
PROFILE_DIR="${RB_PROFILE_DIR:-/tmp/rb-chrome-profile}"
SCREEN="${RB_SCREEN:-1920x1080x24}"
DISPLAY_NUM="${RB_DISPLAY:-:99}"
HEADLESS="${RB_HEADLESS:-0}"

log() { echo "[start-chromium] $*"; }

if [ ! -x "$CHROME_BIN" ]; then
  log "错误: 找不到可执行的 Chromium: $CHROME_BIN"
  ls -la "$(dirname "$CHROME_BIN")" || true
  exit 1
fi

mkdir -p "$PROFILE_DIR"

XVFB_PID=""
CHROME_PID=""
RELAY_PID=""

cleanup() {
  for pid in "$RELAY_PID" "$CHROME_PID" "$XVFB_PID"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT INT TERM

if [ "$HEADLESS" = "1" ]; then
  log "启动 Chromium（headless=new 模式，无 Xvfb）"
  "$CHROME_BIN" "$@" &
  CHROME_PID=$!
else
  log "启动 Xvfb $DISPLAY_NUM ($SCREEN)"
  Xvfb "$DISPLAY_NUM" -screen 0 "$SCREEN" -nolisten tcp -ac \
    >/tmp/rb-xvfb.log 2>&1 &
  XVFB_PID=$!
  export DISPLAY="$DISPLAY_NUM"

  for _ in $(seq 1 50); do
    if [ -S "/tmp/.X11-unix/X${DISPLAY_NUM#:}" ]; then break; fi
    sleep 0.1
  done
  if ! [ -S "/tmp/.X11-unix/X${DISPLAY_NUM#:}" ]; then
    log "错误: Xvfb 未启动成功，日志："
    cat /tmp/rb-xvfb.log || true
    exit 1
  fi

  log "启动 Chromium（有头模式，DISPLAY=$DISPLAY_NUM）"
  "$CHROME_BIN" "$@" &
  CHROME_PID=$!
fi

# 等待 DevTools 端口就绪（最多 30 秒）
READY=0
for _ in $(seq 1 150); do
  if ! kill -0 "$CHROME_PID" 2>/dev/null; then
    log "错误: Chromium 进程已退出（启动失败）"
    exit 1
  fi
  if curl -sf -m 1 "http://127.0.0.1:${CDP_PORT}/json/version" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 0.2
done

if [ "$READY" != "1" ]; then
  log "错误: ${CDP_PORT} 端口在 30 秒内未就绪"
  exit 1
fi

log "Chromium DevTools 已就绪：127.0.0.1:${CDP_PORT}"
RB_CDP_PORT="$CDP_PORT" RB_CDP_RELAY_PORT="$RELAY_PORT" \
  node /opt/rb/cdp-relay.js &
RELAY_PID=$!

log "CDP relay 已启动：0.0.0.0:${RELAY_PORT} -> 127.0.0.1:${CDP_PORT}"

# 容器生命周期跟随 Chromium
wait "$CHROME_PID"
log "Chromium 已退出，容器即将结束"
