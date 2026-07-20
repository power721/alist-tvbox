#!/usr/bin/env bash
# xs-version-sync.sh — 潇洒本地包地址同步（生产端）
#
# 在 d.har01d.cn 服务器由 cron 运行，产出 /var/www/alist/xs.txt，
# 供 alist-tvbox 消费端（FileDownloader.resolveXsSingleUrl）运行时读取。
#
# 流程：
#   1. 读本地版本 /var/www/alist/xs.version.txt（不存在视为 0，触发首次下载）
#   2. 拉上游版本 $XS_BASE/version.txt
#   3. 不同则：拉 single.json → 取 zip 地址 → 下载 zip → 解压
#      → 从 api.json 取 sites[].ext（版本•信息，优先 api==csp_Market）
#      → 原子写 /var/www/alist/xs.txt，更新 xs.version.txt
#
# ⚠️ 只能检测「同一 XS_BASE 内」的版本号变化。上游换 host/路径时，改下面的 XS_BASE
#    （或用环境变量 XS_BASE=... 覆盖）。这是唯一需要人工介入的情形。
#
# 依赖：curl、python3、unzip。需以对 /var/www/alist 有写权限的身份运行。
# cron 示例（每 6 小时）：  0 */6 * * * /path/to/xs-version-sync.sh >> /var/log/xs-sync.log 2>&1

set -euo pipefail

# ===== 配置 =====
XS_BASE="${XS_BASE:-https://oss-v1.wangmeipo.cn/236}"   # 上游基址（version.txt / single.json 所在目录）
USER_AGENT="okhttp/5.3.2"
OUT_DIR="/var/www/alist"
XS_TXT="$OUT_DIR/xs.txt"                 # 产物：消费端读取的 single.json 地址
VERSION_FILE="$OUT_DIR/xs.version.txt"   # 本地已知版本

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

log() { echo "[$(date '+%F %T')] $*" >&2; }

# 1. 本地版本
local_version="0"
[[ -f "$VERSION_FILE" ]] && local_version="$(tr -d '[:space:]' < "$VERSION_FILE")"

# 2. 上游版本
online_version="$(curl -fsSL --connect-timeout 15 --max-time 30 -A "$USER_AGENT" "$XS_BASE/version.txt" | tr -d '[:space:]')" || {
    log "拉取上游版本失败：$XS_BASE/version.txt"
    exit 1
}

log "local=$local_version online=$online_version"

# 3. 相同则跳过
if [[ "$online_version" == "$local_version" ]]; then
    log "版本相同，跳过"
    exit 0
fi

# 4. 拉 single.json，取 zip 地址（本地包 → 点击下载 → url），逻辑同 FileDownloader.getXsDownloadUrl
single_json="$WORK_DIR/single.json"
curl -fsSL --connect-timeout 15 --max-time 30 -A "$USER_AGENT" "$XS_BASE/single.json" -o "$single_json" || {
    log "拉取 single.json 失败：$XS_BASE/single.json"
    exit 1
}

zip_url="$(python3 - "$single_json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
for section in data:
    if section.get("name") == "本地包":
        for item in section.get("list", []):
            if item.get("name") == "点击下载":
                url = item.get("url", "")
                if url:
                    print(url)
                    sys.exit(0)
sys.exit("Cannot find xs download url from single.json")
PY
)" || { log "解析 zip 地址失败"; exit 1; }

log "zip_url=$zip_url"

# 5. 下载 zip
xs_zip="$WORK_DIR/xs.zip"
curl -fsSL --connect-timeout 15 --max-time 120 -A "$USER_AGENT" "$zip_url" -o "$xs_zip" || {
    log "下载 zip 失败：$zip_url"
    exit 1
}

# 6. 解压
mkdir -p "$WORK_DIR/extract"
unzip -o -q "$xs_zip" -d "$WORK_DIR/extract" || { log "解压失败：$xs_zip"; exit 1; }

# 7. 找 api.json（递归，兼容不同包内布局，如 TVBoxOSC/tvbox/api.json）
api_json="$(find "$WORK_DIR/extract" -type f -name api.json | head -1)"
[[ -n "$api_json" ]] || { log "未在 zip 中找到 api.json"; exit 1; }

# 8. 取 ext（优先 api==csp_Market，其次 name 含「版本」）
new_ext="$(python3 - "$api_json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
sites = data.get("sites", [])
for s in sites:
    if s.get("api") == "csp_Market":
        ext = s.get("ext", "")
        if isinstance(ext, str) and ext:
            print(ext); sys.exit(0)
for s in sites:
    if "版本" in (s.get("name") or ""):
        ext = s.get("ext", "")
        if isinstance(ext, str) and ext:
            print(ext); sys.exit(0)
sys.exit("Cannot find ext (版本信息) in api.json")
PY
)" || { log "解析 ext 失败：$api_json"; exit 1; }

# 9. 原子落盘：全部成功才覆盖，绝不用空/错误数据冲掉现有 xs.txt
printf '%s\n' "$new_ext" > "$WORK_DIR/xs.txt.new"
mv -f "$WORK_DIR/xs.txt.new" "$XS_TXT"

printf '%s\n' "$online_version" > "$WORK_DIR/xs.version.txt.new"
mv -f "$WORK_DIR/xs.version.txt.new" "$VERSION_FILE"

log "已更新 $XS_TXT -> $new_ext ；版本 $local_version -> $online_version"
