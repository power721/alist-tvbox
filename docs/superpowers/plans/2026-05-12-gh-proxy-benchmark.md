# GitHub Proxy Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a repository Bash script that discovers GitHub proxy nodes from `https://api.akams.cn/github`, benchmarks them against the fixed `spiders_v2.json` target, and ranks results by first-byte time then total time.

**Architecture:** Keep the feature isolated in a single operational Bash script under `scripts/`, but make it sourceable so a tiny Bash test script can exercise parsing, fallback, URL building, and sorting without hitting the network. Use `curl` for live benchmarking, `jq` when present for JSON parsing, and a `python3` fallback parser when `jq` is unavailable.

**Tech Stack:** Bash, `curl`, `jq` optional, `python3` fallback, coreutils (`sort`, `printf`, `mktemp`).

---

### Task 1: Add a Sourceable Script Skeleton and Bash Test Harness

**Files:**
- Create: `scripts/gh_proxy_bench.sh`
- Create: `scripts/tests/gh_proxy_bench_test.sh`

- [ ] **Step 1: Write the failing Bash test harness**

```bash
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
  assert_eq $'默认节点\tgh.llkk.cc' "$actual" "fallback_nodes should expose the built-in fallback host"
}

test_sort_success_rows() {
  local input expected actual
  input=$'公益贡献\thk.example\t200\t0.220\t0.440\thttps://hk.example/example\n默认节点\tgh.llkk.cc\t200\t0.110\t0.330\thttps://gh.llkk.cc/example\n搜索引擎\tsearch.example\t200\t0.110\t0.350\thttps://search.example/example'
  expected=$'默认节点\tgh.llkk.cc\t200\t0.110\t0.330\thttps://gh.llkk.cc/example\n搜索引擎\tsearch.example\t200\t0.110\t0.350\thttps://search.example/example\n公益贡献\thk.example\t200\t0.220\t0.440\thttps://hk.example/example'
  actual="$(sort_success_rows <<<"$input")"
  assert_eq "$expected" "$actual" "sort_success_rows should order by starttransfer then total then host"
}

test_build_proxy_url
test_normalize_label
test_fallback_nodes
test_sort_success_rows

printf 'gh_proxy_bench tests: PASS\n'
```

- [ ] **Step 2: Run the test harness to verify it fails**

Run: `bash scripts/tests/gh_proxy_bench_test.sh`
Expected: FAIL because `scripts/gh_proxy_bench.sh` does not exist yet.

- [ ] **Step 3: Add the minimal sourceable script skeleton**

```bash
#!/usr/bin/env bash
set -euo pipefail

readonly GH_PROXY_API_URL="https://api.akams.cn/github"
readonly GH_PROXY_TARGET_URL="https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json"

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
  printf '默认节点\tgh.llkk.cc\n'
}

build_proxy_url() {
  local host="$1"
  printf 'https://%s/%s\n' "$host" "$GH_PROXY_TARGET_URL"
}

sort_success_rows() {
  sort -t $'\t' -k4,4n -k5,5n -k2,2
}

main() {
  printf 'gh_proxy_bench skeleton\n'
}

if [[ "${GH_PROXY_BENCH_SOURCE_ONLY:-0}" != "1" ]]; then
  main "$@"
fi
```

- [ ] **Step 4: Run the test harness to verify it passes**

Run: `bash scripts/tests/gh_proxy_bench_test.sh`
Expected: PASS with `gh_proxy_bench tests: PASS`

- [ ] **Step 5: Commit**

```bash
git add scripts/gh_proxy_bench.sh scripts/tests/gh_proxy_bench_test.sh
git commit -m "feat: add gh proxy benchmark skeleton"
```

### Task 2: Implement Runtime Node Discovery and API Fallback

**Files:**
- Modify: `scripts/gh_proxy_bench.sh`
- Modify: `scripts/tests/gh_proxy_bench_test.sh`

- [ ] **Step 1: Extend the tests with JSON parsing expectations**

```bash
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

test_parse_nodes_with_jq_or_python
test_parse_nodes_rejects_bad_payload
```

- [ ] **Step 2: Run the test harness to verify it fails**

Run: `bash scripts/tests/gh_proxy_bench_test.sh`
Expected: FAIL because `parse_nodes_from_payload` is not defined yet.

- [ ] **Step 3: Implement payload parsing and API discovery**

```bash
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
  local rows
  if command -v jq >/dev/null 2>&1; then
    rows="$(parse_nodes_with_jq "$payload")"
  else
    rows="$(parse_nodes_with_python "$payload" | while IFS=$'\t' read -r raw_tag host; do
      printf '%s\t%s\n' "$(normalize_label "$host" "$raw_tag")" "$host"
    done)"
  fi
  printf '%s\n' "$rows" | awk 'NF > 0'
}

discover_nodes() {
  local payload rows
  if payload="$(curl --location --silent --show-error --fail "$GH_PROXY_API_URL")"; then
    rows="$(parse_nodes_from_payload "$payload" || true)"
    if [[ -n "$rows" ]]; then
      printf '%s\n' "$rows"
      return 0
    fi
  fi
  fallback_nodes
}
```

- [ ] **Step 4: Run the test harness to verify it passes**

Run: `bash scripts/tests/gh_proxy_bench_test.sh`
Expected: PASS with `gh_proxy_bench tests: PASS`

- [ ] **Step 5: Commit**

```bash
git add scripts/gh_proxy_bench.sh scripts/tests/gh_proxy_bench_test.sh
git commit -m "feat: add gh proxy node discovery"
```

### Task 3: Implement Single-Node Benchmarking, Success/Failure Capture, and Sorting

**Files:**
- Modify: `scripts/gh_proxy_bench.sh`
- Modify: `scripts/tests/gh_proxy_bench_test.sh`

- [ ] **Step 1: Add failing tests for benchmark row formatting**

```bash
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

test_parse_curl_success_metrics
test_parse_curl_failure_metrics
```

- [ ] **Step 2: Run the test harness to verify it fails**

Run: `bash scripts/tests/gh_proxy_bench_test.sh`
Expected: FAIL because `format_success_row` and `format_failure_row` are not defined yet.

- [ ] **Step 3: Implement the benchmark helpers**

```bash
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
  local url curl_output curl_status
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

  format_success_row "$label" "$host" "$curl_output"
}
```

- [ ] **Step 4: Run the test harness to verify it passes**

Run: `bash scripts/tests/gh_proxy_bench_test.sh`
Expected: PASS with `gh_proxy_bench tests: PASS`

- [ ] **Step 5: Commit**

```bash
git add scripts/gh_proxy_bench.sh scripts/tests/gh_proxy_bench_test.sh
git commit -m "feat: add gh proxy benchmark metrics"
```

### Task 4: Implement CLI Output, End-to-End Main Flow, and Verification

**Files:**
- Modify: `scripts/gh_proxy_bench.sh`

- [ ] **Step 1: Add the final CLI output implementation**

```bash
print_success_table() {
  local rows="$1"
  printf 'Success Nodes\n'
  printf '%-12s %-28s %-6s %-10s %-10s %s\n' "Label" "Host" "HTTP" "TTFB(s)" "Total(s)" "URL"
  while IFS=$'\t' read -r label host status ttfb total url; do
    [[ -n "${host:-}" ]] || continue
    printf '%-12s %-28s %-6s %-10s %-10s %s\n' "$label" "$host" "$status" "$ttfb" "$total" "$url"
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

main() {
  local discovered success_rows="" failure_rows=""
  discovered="$(discover_nodes)"

  while IFS=$'\t' read -r label host; do
    [[ -n "${host:-}" ]] || continue
    if row="$(benchmark_host "$label" "$host")"; then
      success_rows+="${row}"$'\n'
    else
      failure_rows+="${row}"$'\n'
    fi
  done <<<"$discovered"

  if [[ -n "$success_rows" ]]; then
    success_rows="$(printf '%s' "$success_rows" | awk 'NF > 0' | sort_success_rows)"
    print_success_table "$success_rows"
  else
    printf 'No successful proxy nodes.\n'
  fi

  print_failure_table "$(printf '%s' "$failure_rows" | awk 'NF > 0')"
}
```

- [ ] **Step 2: Run syntax and unit-style checks**

Run: `bash -n scripts/gh_proxy_bench.sh && bash scripts/tests/gh_proxy_bench_test.sh`
Expected: PASS with no syntax errors and `gh_proxy_bench tests: PASS`

- [ ] **Step 3: Run the live benchmark against the remote API**

Run: `bash scripts/gh_proxy_bench.sh`
Expected:
- Prints a `Success Nodes` table
- If some nodes are unavailable, also prints `Failed Nodes`
- Successful rows are ordered by first-byte time then total time

- [ ] **Step 4: Run a controlled fallback verification**

```bash
GH_PROXY_BENCH_SOURCE_ONLY=1 bash -lc '
  source scripts/gh_proxy_bench.sh
  curl() { return 22; }
  discover_nodes
'
```
Expected: prints at least `默认节点    gh.llkk.cc`, proving the fallback path still works when API fetch fails.

- [ ] **Step 5: Commit**

```bash
git add scripts/gh_proxy_bench.sh scripts/tests/gh_proxy_bench_test.sh
git commit -m "feat: add github proxy benchmark script"
```
