#!/system/bin/sh
MODDIR="${0%/*}"
LOGDIR="$MODDIR/logs"
LOGFILE="$LOGDIR/boot.log"
mkdir -p "$LOGDIR"
log() { echo "[$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)] $1" >> "$LOGFILE"; }
COUNT=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ] && [ "$COUNT" -lt 90 ]; do
  sleep 2
  COUNT=$((COUNT + 1))
done
ABI="$(getprop ro.product.cpu.abi 2>/dev/null)"
API="$(getprop ro.build.version.sdk 2>/dev/null)"
MODEL="$(getprop ro.product.model 2>/dev/null)"
log "GORILLA module service started"
log "Device: ${MODEL:-unknown}; ABI: ${ABI:-unknown}; Android API: ${API:-unknown}"
[ "$ABI" = "arm64-v8a" ] && log "Architecture check: PASS" || log "Architecture check: FAIL (ARM64 required)"
if [ -d /data/adb/ksu ] || command -v ksud >/dev/null 2>&1; then
  log "KernelSU marker: detected"
else
  log "KernelSU marker: not detected by basic path check"
fi
if command -v pm >/dev/null 2>&1 && pm path com.pubg.krmobile >/dev/null 2>&1; then
  VERSION="$(dumpsys package com.pubg.krmobile 2>/dev/null | grep -m 1 'versionName=' | cut -d= -f2)"
  log "PUBG Mobile KR package detected; versionName=${VERSION:-unavailable}"
  case "$VERSION" in
    *4.7*) log "Version string contains 4.7; this is not a gameplay compatibility certification." ;;
    *) log "Requested 4.7 version was not confirmed by versionName; no compatibility claim made." ;;
  esac
else
  log "PUBG Mobile KR package com.pubg.krmobile not detected"
fi
log "No game process was opened, inspected, modified, or controlled."
log "Service completed; no persistent performance or system tuning was applied."
