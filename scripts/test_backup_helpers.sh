#!/usr/bin/env bash
set -euo pipefail
ALIST_TVBOX_SOURCE_ONLY=1 source "$(dirname "$0")/alist-tvbox.sh"
fail=0
check() { [[ "$1" == "$2" ]] || { echo "FAIL: $(basename "$3") expected $2 got $1"; fail=1; }; }
check "$(detect_backup_type "/x/database-json-2026-06-19.zip")" "JSON" "json"
check "$(detect_backup_type "/x/database-2026-06-20-091200.zip")" "SQL" "sql"
check "$(detect_backup_type "/x/database-json-old.zip")" "JSON" "json2"
check "$(detect_backup_type "/x/foo.zip")" "SQL" "foo"
[[ $fail -eq 0 ]] && { echo "PASS"; exit 0; } || exit 1
