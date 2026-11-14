#!/system/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MODULE="$ROOT/module"
for file in module.prop customize.sh post-fs-data.sh service.sh uninstall.sh bin/status.sh config/defaults.properties; do
  test -f "$MODULE/$file" || { echo "Missing: $file"; exit 1; }
done
for file in customize.sh post-fs-data.sh service.sh uninstall.sh bin/status.sh; do sh -n "$MODULE/$file"; done
grep -q '^id=gorilla_pubg_companion$' "$MODULE/module.prop"
grep -q '^esp.test_scene_only=true$' "$MODULE/config/defaults.properties"
echo "Module structure and shell syntax: PASS"
