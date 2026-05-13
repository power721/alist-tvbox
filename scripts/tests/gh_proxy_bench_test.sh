#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export GH_PROXY_BENCH_SOURCE_ONLY=1
# shellcheck source=/dev/null
source "$ROOT_DIR/scripts/gh_proxy_bench.sh"

assert_eq() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  if [[ "$expected" != "$actual" ]]; then
    printf 'ASSERT FAIL: %s\nexpected: [%s]\nactual:   [%s]\n' "$message" "$expected" "$actual" >&2
    exit 1
  fi
}

test_build_proxy_url() {
  local actual
  actual="$(build_proxy_url "gh.llkk.cc")"
  assert_eq \
    "https://gh.llkk.cc/https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json" \
    "$actual" \
    "build_proxy_url should prepend host to fixed target"
}

test_normalize_label() {
  assert_eq "默认节点" "$(normalize_label "gh.llkk.cc" "random-tag")" "default host should override tag"
  assert_eq "公益贡献" "$(normalize_label "edge.example" "donate")" "donate tag should map to 公益贡献"
  assert_eq "search" "$(normalize_label "edge.example" "search")" "other tags should be preserved"
}

test_fallback_nodes() {
  local actual
  actual="$(fallback_nodes)"
  assert_eq $'默认节点\tgh.llkk.cc\n备用节点\tgh-proxy.org\n备用节点\thk.gh-proxy.org\n备用节点\tcdn.gh-proxy.org\n备用节点\tedgeone.gh-proxy.org\n备用节点\tgh.felicity.ac.cn' "$actual" "fallback_nodes should expose the full built-in fallback pool"
}

test_sort_success_rows() {
  local input expected actual
  input=$'公益贡献\thk.example\t200\t0.220\t0.440\thttps://hk.example/example\n默认节点\tgh.llkk.cc\t200\t0.110\t0.330\thttps://gh.llkk.cc/example\n搜索引擎\tsearch.example\t200\t0.110\t0.350\thttps://search.example/example'
  expected=$'默认节点\tgh.llkk.cc\t200\t0.110\t0.330\thttps://gh.llkk.cc/example\n搜索引擎\tsearch.example\t200\t0.110\t0.350\thttps://search.example/example\n公益贡献\thk.example\t200\t0.220\t0.440\thttps://hk.example/example'
  actual="$(sort_success_rows <<<"$input")"
  assert_eq "$expected" "$actual" "sort_success_rows should order by starttransfer then total then host"
}

test_parse_nodes_with_jq_or_python() {
  local payload actual expected
  payload='{"code":200,"data":[{"url":"https://gh.llkk.cc/https://github.com/example","tag":"donate"},{"url":"https://edgeone.gh-proxy.org/https://github.com/example","tag":"search"},{"url":"https://gh.llkk.cc/https://github.com/example","tag":"duplicate"}]}'
  expected=$'默认节点\tgh.llkk.cc\nsearch\tedgeone.gh-proxy.org'
  actual="$(parse_nodes_from_payload "$payload")"
  assert_eq "$expected" "$actual" "parse_nodes_from_payload should extract unique hosts and normalized labels"
}

test_parse_nodes_rejects_bad_payload() {
  local actual
  actual="$(parse_nodes_from_payload '{"code":500,"data":[]}' || true)"
  assert_eq "" "$actual" "parse_nodes_from_payload should emit nothing for unusable payloads"
}

test_discover_nodes_uses_embedded_payload_without_curl() {
  local actual expected
  curl() {
    printf 'ASSERT FAIL: discover_nodes should not call curl\n' >&2
    exit 1
  }

  actual="$(discover_nodes)"
  expected="$(
    {
      parse_nodes_from_payload "$(embedded_nodes_payload)"
      fallback_nodes
    } | awk -F '\t' 'NF > 0 && !seen[$2]++'
  )"
  assert_eq "$expected" "$actual" "discover_nodes should merge fallback nodes into the embedded list without duplicates"
}

test_discover_nodes_excludes_removed_hosts() {
  local output removed_hosts host
  output="$(discover_nodes)"
  removed_hosts=(
    ghpr.cc
    gh-proxy.net
    ghproxy.net
    git.669966.xyz
    g.blfrp.cn
    gh.927223.xyz
    gh.bugdey.us.kg
    github.ednovas.xyz
    github.xxlab.tech
    proxy.yaoyaoling.net
    gh.monlor.com
    gh.ddlc.top
    gh.catmak.name
    free.cn.eu.org
    ghpxy.hwinzniej.top
    j.1win.ggff.net
    github.geekery.cn
    gp.zkitefly.eu.org
    ghp.keleyaa.com
  )

  for host in "${removed_hosts[@]}"; do
    if [[ "$output" == *$'\t'"$host"$'\n'* ]] || [[ "$output" == *$'\t'"$host" ]]; then
      printf 'ASSERT FAIL: discover_nodes should exclude removed host [%s]\n' "$host" >&2
      exit 1
    fi
  done
}

test_parse_curl_success_metrics() {
  local metrics actual expected
  metrics=$'200\t0.123456\t0.456789'
  expected=$'默认节点\tgh.llkk.cc\t200\t0.123\t0.457\thttps://gh.llkk.cc/https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json'
  actual="$(format_success_row "默认节点" "gh.llkk.cc" "$metrics")"
  assert_eq "$expected" "$actual" "format_success_row should normalize curl metrics to 3 decimals"
}

test_parse_curl_failure_metrics() {
  local actual expected
  expected=$'gh.llkk.cc\tcurl_exit_28'
  actual="$(format_failure_row "gh.llkk.cc" "curl_exit_28")"
  assert_eq "$expected" "$actual" "format_failure_row should preserve host and failure reason"
}

test_print_success_table() {
  local rows output
  rows=$'默认节点\tgh.llkk.cc\t200\t0.123\t0.456\thttps://gh.llkk.cc/example'
  output="$(print_success_table "$rows")"
  [[ "$output" == *"Success Nodes"* ]] || {
    printf 'ASSERT FAIL: print_success_table should print section title\n' >&2
    exit 1
  }
  [[ "$output" != *"Label"* ]] || {
    printf 'ASSERT FAIL: print_success_table should not print the Label column\n' >&2
    exit 1
  }
  [[ "$output" != *"默认节点"* ]] || {
    printf 'ASSERT FAIL: print_success_table should not print label values in rows\n' >&2
    exit 1
  }
  [[ "$output" == *"gh.llkk.cc"* ]] || {
    printf 'ASSERT FAIL: print_success_table should include row host\n' >&2
    exit 1
  }
}

test_print_failure_table() {
  local rows output
  rows=$'gh.llkk.cc\tcurl_exit_28'
  output="$(print_failure_table "$rows")"
  [[ "$output" == *"Failed Nodes"* ]] || {
    printf 'ASSERT FAIL: print_failure_table should print section title\n' >&2
    exit 1
  }
  [[ "$output" == *"curl_exit_28"* ]] || {
    printf 'ASSERT FAIL: print_failure_table should include failure reason\n' >&2
    exit 1
  }
}

test_result_file_path() {
  local path
  path="$(result_file_path)"
  assert_eq \
    "$ROOT_DIR/scripts/gh_proxy_bench_result.json" \
    "$path" \
    "result_file_path should resolve the fixed JSON output next to the script"
}

test_render_json_report() {
  local success_rows failure_rows output
  success_rows=$'默认节点\tgh.llkk.cc\t200\t0.123\t0.456\thttps://gh.llkk.cc/example'
  failure_rows=$'ghpr.cc\thttp_404'
  output="$(render_json_report "2026-05-12T18:00:00+08:00" "$success_rows" "$failure_rows")"

  [[ "$output" == *'"target_url": "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json"'* ]] || {
    printf 'ASSERT FAIL: render_json_report should include target_url\n' >&2
    exit 1
  }
  [[ "$output" == *'"discovery_api": "embedded://gh-proxy-nodes-2026-05-12"'* ]] || {
    printf 'ASSERT FAIL: render_json_report should include embedded discovery source\n' >&2
    exit 1
  }
  [[ "$output" == *'"host": "gh.llkk.cc"'* ]] || {
    printf 'ASSERT FAIL: render_json_report should include success host\n' >&2
    exit 1
  }
  [[ "$output" == *'"reason": "http_404"'* ]] || {
    printf 'ASSERT FAIL: render_json_report should include failure reason\n' >&2
    exit 1
  }
}

test_build_proxy_url
test_normalize_label
test_fallback_nodes
test_sort_success_rows
test_parse_nodes_with_jq_or_python
test_parse_nodes_rejects_bad_payload
test_discover_nodes_uses_embedded_payload_without_curl
test_discover_nodes_excludes_removed_hosts
test_parse_curl_success_metrics
test_parse_curl_failure_metrics
test_print_success_table
test_print_failure_table
test_result_file_path
test_render_json_report

printf 'gh_proxy_bench tests: PASS\n'
