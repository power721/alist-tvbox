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
#      → 镜像 zip 与改写后的市场 json 到本站（xs-package.zip / xs-single.json）
#      → 原子写 /var/www/alist/xs.txt（固定指向本站镜像），更新 xs.version.txt
#
# ⚠️ 只能检测「同一 XS_BASE 内」的版本号变化。上游换 host/路径时，改下面的 XS_BASE
#    （或用环境变量 XS_BASE=... 覆盖）。这是唯一需要人工介入的情形。
#
# 依赖：curl、python3、unzip。需以对 /var/www/alist 有写权限的身份运行。
# cron 示例（每小时）：  0 * * * * /var/www/alist/xs-version-sync.sh >> /var/www/alist/xs-sync.log 2>&1

set -euo pipefail

# ===== 配置 =====
# 上游基址（version.txt / single.json 所在目录）。
# 迁移史：pizazz.s3.bitiful.net → 9877.kstore.space → oss-v1.wangmeipo.cn/236
#         → 2026-09-03 回归 9877.kstore.space（zip 本体另行迁移至 pizazz.us.ci/单线路.zip）
# 2026-09-15：潇洒在线接口迁至 https://9877.kstore.space/sun.json（2423 加密配置，FongMi 系客户端原生解密），
#         消费端 FileDownloader 双模式化：指针按市场（zip 流程）/按配置（直接落盘）均可工作。
# 2026-10-17：镜像化改造。家宽/企业路由器安全过滤（趋势科技 AiProtection 类，cat_id=75）大量拦截
#         kstore.space 整域——消费端 TLS 握手失败、潇洒包无法更新（上游版本同步本身正常）。
#         本脚本已下载 zip，索性把 zip（xs-package.zip）与改写「点击下载」地址后的市场 json
#         （xs-single.json）一并镜像到本站，xs.txt 固定指向 xs-single.json——消费端全程只访问
#         d.har01d.cn，不再直连上游。上游 zip 内 api.json 的 csp_Market ext 仅记日志不再写入 xs.txt。
XS_BASE="${XS_BASE:-https://9877.kstore.space}"
XS_MIRROR_BASE="${XS_MIRROR_BASE:-https://d.har01d.cn}"
USER_AGENT="okhttp/5.3.2"
OUT_DIR="${OUT_DIR:-/var/www/alist}"
XS_TXT="$OUT_DIR/xs.txt"                 # 产物：消费端读取的市场 json 地址（本站镜像）
VERSION_FILE="$OUT_DIR/xs.version.txt"   # 本地已知版本
XS_MIRROR_ZIP="$OUT_DIR/xs-package.zip"  # 镜像：上游本地包 zip 本体
XS_MIRROR_SINGLE="$OUT_DIR/xs-single.json"  # 镜像：上游市场 json（点击下载已改指本站 zip）

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

# 8. 取 ext（优先 api==csp_Market，其次 name 含「版本」）——仅记日志观察上游指针动向
upstream_ext="$(python3 - "$api_json" <<'PY'
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

log "上游 ext=$upstream_ext（不再写入 xs.txt，仅观察）"

# 9. 镜像产物：市场 json 的「点击下载」改指本站 zip，连同 zip 本体一并落本站
mirror_single="$WORK_DIR/xs-single.json"
python3 - "$single_json" "$XS_MIRROR_BASE/xs-package.zip" "$mirror_single" <<'PY'
import json, sys
src, zip_url, dst = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.load(open(src, encoding="utf-8"))
replaced = False
for section in data:
    if section.get("name") == "本地包":
        for item in section.get("list", []):
            if item.get("name") == "点击下载":
                item["url"] = zip_url
                replaced = True
if not replaced:
    sys.exit("Cannot find 本地包/点击下载 to rewrite in single.json")
json.dump(data, open(dst, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY

# 10. 原子落盘：全部成功才覆盖，绝不用空/错误数据冲掉现有产物。
#     顺序：先 zip、后市场 json、最后 xs.txt——xs.txt 指到的资源必须先就位。
cp -f "$xs_zip" "$XS_MIRROR_ZIP.new"
mv -f "$XS_MIRROR_ZIP.new" "$XS_MIRROR_ZIP"
cp -f "$mirror_single" "$XS_MIRROR_SINGLE.new"
mv -f "$XS_MIRROR_SINGLE.new" "$XS_MIRROR_SINGLE"

printf '%s\n' "$XS_MIRROR_BASE/xs-single.json" > "$WORK_DIR/xs.txt.new"
mv -f "$WORK_DIR/xs.txt.new" "$XS_TXT"

printf '%s\n' "$online_version" > "$WORK_DIR/xs.version.txt.new"
mv -f "$WORK_DIR/xs.version.txt.new" "$VERSION_FILE"

log "已镜像 $XS_MIRROR_SINGLE + $XS_MIRROR_ZIP；xs.txt -> $XS_MIRROR_BASE/xs-single.json；版本 $local_version -> $online_version"
