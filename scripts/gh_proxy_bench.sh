#!/usr/bin/env bash
set -euo pipefail

readonly GH_PROXY_DISCOVERY_SOURCE="embedded://gh-proxy-nodes-2026-05-12"
readonly GH_PROXY_TARGET_URL="https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json"

script_dir() {
  cd "$(dirname "${BASH_SOURCE[0]}")" && pwd
}

result_file_path() {
  printf '%s/gh_proxy_bench_result.json\n' "$(script_dir)"
}

normalize_label() {
  local host="$1"
  local tag="${2:-}"
  if [[ "$host" == "gh.llkk.cc" ]]; then
    printf '默认节点\n'
  elif [[ "$tag" == "donate" ]]; then
    printf '公益贡献\n'
  else
    printf '%s\n' "${tag:-未命名节点}"
  fi
}

fallback_nodes() {
  printf '%s\n' \
    $'默认节点\tgh.llkk.cc' \
    $'备用节点\tgh-proxy.org' \
    $'备用节点\thk.gh-proxy.org' \
    $'备用节点\tcdn.gh-proxy.org' \
    $'备用节点\tedgeone.gh-proxy.org' \
    $'备用节点\tgh.felicity.ac.cn'
}

embedded_nodes_payload() {
  cat <<'JSON'
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "url": "https://slink.ltd",
      "server": "Angie",
      "ip": "157.90.33.73",
      "location": "Germany Saxony Falkenstein",
      "latency": 258,
      "speed": 21059.55,
      "tag": "search"
    },
    {
      "url": "https://github.starrlzy.cn",
      "server": "nginx",
      "ip": "64.83.33.87",
      "location": "United States  ",
      "latency": 160,
      "speed": 3171.13,
      "tag": "donate"
    },
    {
      "url": "https://gh.tryxd.cn",
      "server": "edgeone-pages",
      "ip": "115.238.171.185",
      "location": "中国 浙江省 宁波市",
      "latency": 207,
      "speed": 2099.59,
      "tag": "search"
    },
    {
      "url": "https://gitproxy.click",
      "server": "Cowboy",
      "ip": "212.92.104.2",
      "location": "Netherlands  ",
      "latency": 316,
      "speed": 1611.47,
      "tag": "search"
    },
    {
      "url": "https://github.dpik.top",
      "server": "cloudflare",
      "ip": "162.159.33.55",
      "location": "  ",
      "latency": 143,
      "speed": 3.98,
      "tag": "donate"
    },
    {
      "url": "https://ghm.078465.xyz",
      "server": "cloudflare",
      "ip": "162.159.45.234",
      "location": "  ",
      "latency": 107,
      "speed": 2.98,
      "tag": "donate"
    },
    {
      "url": "https://github.tbedu.top",
      "server": "cloudflare",
      "ip": "162.159.33.55",
      "location": "  ",
      "latency": 115,
      "speed": 1.74,
      "tag": "donate"
    },
    {
      "url": "https://gh.chjina.com",
      "server": "cloudflare",
      "ip": "172.67.213.202",
      "location": "  ",
      "latency": 221,
      "speed": 1.53,
      "tag": "search"
    },
    {
      "url": "https://github.chenc.dev",
      "server": "Windows-Azure-Blob/1.0 Microsoft-HTTPAPI/2.0",
      "ip": "43.164.132.180",
      "location": "South Korea Seoul Seoul",
      "latency": 180,
      "speed": 1.48,
      "tag": "search"
    },
    {
      "url": "https://gh.nxnow.top",
      "server": "nginx/1.22.1",
      "ip": "141.140.15.12",
      "location": "United States Arizona Phoenix",
      "latency": 226,
      "speed": 1.29,
      "tag": "search"
    },
    {
      "url": "https://ghf.无名氏.top",
      "server": "cloudflare",
      "ip": "162.159.40.183",
      "location": "  ",
      "latency": 382,
      "speed": 1.25,
      "tag": "donate"
    },
    {
      "url": "https://gh.felicity.ac.cn",
      "server": "cloudflare",
      "ip": "162.159.33.30",
      "location": "  ",
      "latency": 138,
      "speed": 1.2,
      "tag": "donate"
    },
    {
      "url": "https://git.yylx.win",
      "server": "cloudflare",
      "ip": "162.159.44.27",
      "location": "  ",
      "latency": 91,
      "speed": 1.14,
      "tag": "donate"
    },
    {
      "url": "https://ghfile.geekertao.top",
      "server": "cloudflare",
      "ip": "162.159.16.182",
      "location": "  ",
      "latency": 91,
      "speed": 0.95,
      "tag": "donate"
    },
    {
      "url": "https://gh.sixyin.com",
      "server": "cloudflare",
      "ip": "162.159.40.183",
      "location": "  ",
      "latency": 145,
      "speed": 0.84,
      "tag": "search"
    },
    {
      "url": "https://gh.dpik.top",
      "server": "cloudflare",
      "ip": "162.159.16.182",
      "location": "  ",
      "latency": 179,
      "speed": 0.59,
      "tag": "donate"
    },
    {
      "url": "https://gitproxy.mrhjx.cn",
      "server": "cloudflare",
      "ip": "104.21.30.97",
      "location": "  ",
      "latency": 232,
      "speed": 0.44,
      "tag": "search"
    },
    {
      "url": "https://ghproxy.1888866.xyz",
      "server": "cloudflare",
      "ip": "104.21.77.107",
      "location": "  ",
      "latency": 261,
      "speed": 0.36,
      "tag": "search"
    },
    {
      "url": "https://gh.llkk.cc",
      "server": "cloudflare",
      "ip": "162.159.44.27",
      "location": "  ",
      "latency": 107,
      "speed": 0.21,
      "tag": "donate"
    },
    {
      "url": "https://jiashu.1win.eu.org",
      "server": "cloudflare",
      "ip": "172.67.165.121",
      "location": "  ",
      "latency": 224,
      "speed": 0.19,
      "tag": "donate"
    },
    {
      "url": "https://gh-proxy.com",
      "server": "cloudflare",
      "ip": "104.26.10.150",
      "location": "  ",
      "latency": 203,
      "speed": 0.15,
      "tag": "donate"
    },
    {
      "url": "https://github-proxy.memory-echoes.cn",
      "server": "cloudflare",
      "ip": "172.67.171.163",
      "location": "  ",
      "latency": 189,
      "speed": 0.14,
      "tag": "donate"
    },
    {
      "url": "https://gh.jasonzeng.dev",
      "server": "cloudflare",
      "ip": "104.21.75.108",
      "location": "  ",
      "latency": 227,
      "speed": 0.12,
      "tag": "search"
    },
    {
      "url": "https://j.1lin.dpdns.org",
      "server": "cloudflare",
      "ip": "172.67.178.20",
      "location": "  ",
      "latency": 188,
      "speed": 0.12,
      "tag": "donate"
    },
    {
      "url": "https://gh.inkchills.cn",
      "server": "cloudflare",
      "ip": "172.67.150.187",
      "location": "  ",
      "latency": 278,
      "speed": 0.11,
      "tag": "donate"
    },
    {
      "url": "https://ghproxy.imciel.com",
      "server": "cloudflare",
      "ip": "104.21.75.115",
      "location": "  ",
      "latency": 260,
      "speed": 0.08,
      "tag": "search"
    },
    {
      "url": "https://tvv.tw",
      "server": "cloudflare",
      "ip": "104.21.70.244",
      "location": "  ",
      "latency": 340,
      "speed": 0.06,
      "tag": "donate"
    },
    {
      "url": "https://gitproxy.127731.xyz",
      "server": "cloudflare",
      "ip": "104.21.12.237",
      "location": "  ",
      "latency": 298,
      "speed": 0.06,
      "tag": "donate"
    },
    {
      "url": "https://cdn.akaere.online",
      "server": "cloudflare",
      "ip": "172.67.134.169",
      "location": "  ",
      "latency": 511,
      "speed": 0.05,
      "tag": "donate"
    },
    {
      "url": "https://ghproxy.cxkpro.top",
      "server": "cloudflare",
      "ip": "104.21.11.89",
      "location": "  ",
      "latency": 215,
      "speed": 0.04,
      "tag": "search"
    },
    {
      "url": "https://fastgit.cc",
      "server": "cloudflare",
      "ip": "104.21.12.221",
      "location": "  ",
      "latency": 263,
      "speed": 0.03,
      "tag": "search"
    },
    {
      "url": "https://gh.idayer.com",
      "server": "cloudflare",
      "ip": "172.67.190.155",
      "location": "  ",
      "latency": 229,
      "speed": 0.03,
      "tag": "search"
    },
    {
      "url": "https://ghfast.top",
      "server": "nginx",
      "ip": "23.95.31.214",
      "location": "United States California Los Angeles",
      "latency": 201,
      "speed": 0.03,
      "tag": "search"
    },
    {
      "url": "https://ghp.arslantu.xyz",
      "server": "cloudflare",
      "ip": "104.21.94.23",
      "location": "  ",
      "latency": 227,
      "speed": 0.03,
      "tag": "search"
    },
    {
      "url": "https://ghproxy.monkeyray.net",
      "server": "nginx",
      "ip": "148.135.124.113",
      "location": "United States California Los Angeles",
      "latency": 273,
      "speed": 0.02,
      "tag": "search"
    },
    {
      "url": "https://gh.noki.icu",
      "server": "istio-envoy",
      "ip": "35.193.113.78",
      "location": "United States Iowa Council Bluffs",
      "latency": 384,
      "speed": 0.01,
      "tag": "search"
    },
    {
      "url": "https://cdn.gh-proxy.com",
      "server": "cloudflare",
      "ip": "146.75.115.52",
      "location": "Japan Tokyo Tokyo",
      "latency": 614,
      "speed": 0.01,
      "tag": "donate"
    }
  ],
  "total": 37,
  "update_time": "2026-05-12 18:25:33"
}
JSON
}

build_proxy_url() {
  local host="$1"
  printf 'https://%s/%s\n' "$host" "$GH_PROXY_TARGET_URL"
}

sort_success_rows() {
  sort -t $'\t' -k4,4n -k5,5n -k2,2
}

merge_node_rows() {
  awk -F '\t' 'NF > 0 && !seen[$2]++'
}

parse_nodes_with_jq() {
  local payload="$1"
  jq -r '
    if (.code == 200 and (.data | type == "array")) then
      .data[]
      | select(.url? and (.url | type == "string"))
      | [.url, (.tag // "")]
      | @tsv
    else
      empty
    end
  ' <<<"$payload" | while IFS=$'\t' read -r raw_url raw_tag; do
    local host
    host="$(python3 -c 'import sys, urllib.parse; print(urllib.parse.urlparse(sys.argv[1]).hostname or "")' "$raw_url")"
    [[ -n "$host" ]] || continue
    printf '%s\t%s\n' "$(normalize_label "$host" "$raw_tag")" "$host"
  done | awk -F '\t' '!seen[$2]++'
}

parse_nodes_with_python() {
  local payload="$1"
  python3 - "$payload" <<'PY'
import json
import sys
from urllib.parse import urlparse

payload = json.loads(sys.argv[1])
if payload.get("code") != 200 or not isinstance(payload.get("data"), list):
    raise SystemExit(0)

seen = set()
for item in payload["data"]:
    raw_url = item.get("url")
    if not isinstance(raw_url, str):
        continue
    host = urlparse(raw_url).hostname or ""
    if not host or host in seen:
        continue
    seen.add(host)
    tag = item.get("tag") or ""
    print(f"{tag}\t{host}")
PY
}

parse_nodes_from_payload() {
  local payload="$1"
  local rows=""
  if command -v jq >/dev/null 2>&1; then
    rows="$(parse_nodes_with_jq "$payload")"
  else
    rows="$(
      parse_nodes_with_python "$payload" | while IFS=$'\t' read -r raw_tag host; do
        printf '%s\t%s\n' "$(normalize_label "$host" "$raw_tag")" "$host"
      done
    )"
  fi

  if [[ -n "$rows" ]]; then
    printf '%s\n' "$rows" | awk 'NF > 0'
  fi
}

discover_nodes() {
  local rows merged_rows
  rows="$(parse_nodes_from_payload "$(embedded_nodes_payload)" || true)"
  merged_rows="$(
    {
      if [[ -n "$rows" ]]; then
        printf '%s\n' "$rows"
      fi
      fallback_nodes
    } | merge_node_rows
  )"

  if [[ -n "$merged_rows" ]]; then
    printf '%s\n' "$merged_rows"
    return 0
  fi

  fallback_nodes
}

format_success_row() {
  local label="$1"
  local host="$2"
  local metrics="$3"
  local status ttfb total url
  IFS=$'\t' read -r status ttfb total <<<"$metrics"
  url="$(build_proxy_url "$host")"
  printf '%s\t%s\t%s\t%.3f\t%.3f\t%s\n' "$label" "$host" "$status" "$ttfb" "$total" "$url"
}

format_failure_row() {
  local host="$1"
  local reason="$2"
  printf '%s\t%s\n' "$host" "$reason"
}

benchmark_host() {
  local label="$1"
  local host="$2"
  local url curl_output curl_status status
  url="$(build_proxy_url "$host")"

  set +e
  curl_output="$(
    curl \
      --location \
      --silent \
      --show-error \
      --output /dev/null \
      --connect-timeout 8 \
      --max-time 30 \
      --write-out $'%{http_code}\t%{time_starttransfer}\t%{time_total}' \
      "$url" 2>&1
  )"
  curl_status=$?
  set -e

  if [[ $curl_status -ne 0 ]]; then
    format_failure_row "$host" "curl_exit_${curl_status}"
    return 1
  fi

  IFS=$'\t' read -r status _ <<<"$curl_output"
  if [[ ! "$status" =~ ^[0-9]{3}$ ]] || (( status >= 400 )) || (( status < 200 )); then
    format_failure_row "$host" "http_${status}"
    return 1
  fi

  format_success_row "$label" "$host" "$curl_output"
}

print_success_table() {
  local rows="$1"
  printf 'Success Nodes\n'
  printf '%-28s %-6s %-10s %-10s %s\n' "Host" "HTTP" "TTFB(s)" "Total(s)" "URL"
  while IFS=$'\t' read -r _label host status ttfb total url; do
    [[ -n "${host:-}" ]] || continue
    printf '%-28s %-6s %-10s %-10s %s\n' "$host" "$status" "$ttfb" "$total" "$url"
  done <<<"$rows"
}

print_failure_table() {
  local rows="$1"
  [[ -n "$rows" ]] || return 0
  printf '\nFailed Nodes\n'
  printf '%-28s %s\n' "Host" "Reason"
  while IFS=$'\t' read -r host reason; do
    [[ -n "${host:-}" ]] || continue
    printf '%-28s %s\n' "$host" "$reason"
  done <<<"$rows"
}

render_json_report() {
  local generated_at="$1"
  local success_rows="$2"
  local failure_rows="$3"

  python3 - "$generated_at" "$GH_PROXY_TARGET_URL" "$GH_PROXY_DISCOVERY_SOURCE" "$success_rows" "$failure_rows" <<'PY'
import json
import sys

generated_at, target_url, discovery_api, success_rows, failure_rows = sys.argv[1:6]

success_nodes = []
for line in success_rows.splitlines():
    if not line.strip():
        continue
    label, host, status, ttfb, total, benchmark_url = line.split("\t")
    success_nodes.append({
        "label": label,
        "host": host,
        "http_status": int(status),
        "ttfb_seconds": float(ttfb),
        "total_seconds": float(total),
        "benchmark_url": benchmark_url,
    })

failed_nodes = []
for line in failure_rows.splitlines():
    if not line.strip():
        continue
    host, reason = line.split("\t", 1)
    failed_nodes.append({
        "host": host,
        "reason": reason,
    })

print(json.dumps({
    "generated_at": generated_at,
    "target_url": target_url,
    "discovery_api": discovery_api,
    "success_nodes": success_nodes,
    "failed_nodes": failed_nodes,
}, ensure_ascii=False, indent=2))
PY
}

write_json_report() {
  local generated_at="$1"
  local success_rows="$2"
  local failure_rows="$3"
  local output_path tmp_file

  output_path="$(result_file_path)"
  tmp_file="$(mktemp "${output_path}.tmp.XXXXXX")"
  render_json_report "$generated_at" "$success_rows" "$failure_rows" >"$tmp_file"
  mv "$tmp_file" "$output_path"
}

main() {
  local discovered row success_rows="" failure_rows="" generated_at
  discovered="$(discover_nodes)"

  while IFS=$'\t' read -r label host; do
    [[ -n "${host:-}" ]] || continue
    if row="$(benchmark_host "$label" "$host")"; then
      success_rows+="${row}"$'\n'
    else
      failure_rows+="${row}"$'\n'
    fi
  done <<<"$discovered"

  success_rows="$(printf '%s' "$success_rows" | awk 'NF > 0')"
  failure_rows="$(printf '%s' "$failure_rows" | awk 'NF > 0')"

  if [[ -n "$success_rows" ]]; then
    success_rows="$(printf '%s\n' "$success_rows" | sort_success_rows)"
    print_success_table "$success_rows"
  else
    printf 'No successful proxy nodes.\n'
  fi

  print_failure_table "$failure_rows"

  generated_at="$(date -Iseconds)"
  write_json_report "$generated_at" "$success_rows" "$failure_rows"
}

if [[ "${GH_PROXY_BENCH_SOURCE_ONLY:-0}" != "1" ]]; then
  main "$@"
fi
