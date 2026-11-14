#!/system/bin/sh
BASE="/data/adb/modules/gorilla_pubg_companion"
ABI="$(getprop ro.product.cpu.abi 2>/dev/null)"
API="$(getprop ro.build.version.sdk 2>/dev/null)"
MODEL="$(getprop ro.product.model 2>/dev/null)"
ROOT_UID="$(id -u 2>/dev/null)"
echo "root_uid=${ROOT_UID:-unknown}"
echo "abi=${ABI:-unknown}"
echo "android_api=${API:-unknown}"
echo "device_model=${MODEL:-unknown}"
if [ -d "$BASE" ]; then
  echo "module_installed=true"
  if [ -f "$BASE/disable" ] || [ -f "$BASE/remove" ]; then
    echo "module_enabled=false"
  else
    echo "module_enabled=true"
  fi
else
  echo "module_installed=false"
  echo "module_enabled=false"
fi
if command -v pm >/dev/null 2>&1 && pm path com.pubg.krmobile >/dev/null 2>&1; then
  echo "kr_package_installed=true"
  VERSION="$(dumpsys package com.pubg.krmobile 2>/dev/null | grep -m 1 'versionName=' | cut -d= -f2)"
  echo "kr_version_name=${VERSION:-unknown}"
else
  echo "kr_package_installed=false"
  echo "kr_version_name=unknown"
fi
