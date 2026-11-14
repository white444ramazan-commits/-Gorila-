#!/system/bin/sh
SKIPUNZIP=0
ui_print " "
ui_print "  GORILLA KernelSU Companion"
ABI="$(getprop ro.product.cpu.abi 2>/dev/null)"
case "$ABI" in
  arm64-v8a) ui_print "- ARM64 device detected." ;;
  *) ui_print "! ARM64 is required; detected ABI: ${ABI:-unknown}." ;;
esac
if [ -z "$KSU" ] && ! command -v ksud >/dev/null 2>&1; then
  ui_print "! KernelSU was not detected by installer environment."
fi
ui_print "- No system partitions or PUBG files will be modified."
