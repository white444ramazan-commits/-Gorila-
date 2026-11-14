#!/system/bin/sh
MODDIR="${0%/*}"
BASE="/data/adb/modules/gorilla_pubg_companion"
mkdir -p "$BASE/config" "$BASE/logs"
if [ ! -f "$BASE/config/settings.properties" ]; then
  cp "$MODDIR/config/defaults.properties" "$BASE/config/settings.properties" 2>/dev/null
fi
chmod 0755 "$MODDIR/bin/status.sh" 2>/dev/null
chmod 0644 "$BASE/config/settings.properties" 2>/dev/null
