# GitHub Proxy Benchmark Result File Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `scripts/gh_proxy_bench.sh` so each run overwrites a fixed `scripts/gh_proxy_bench_result.json` file while preserving the current terminal output.

**Architecture:** Keep the current Bash collection flow, then add a small serialization layer that converts the final success and failure row sets into one JSON document. Resolve the JSON output path relative to the script location, write into a temporary file, and atomically replace the fixed destination after serialization succeeds.

**Tech Stack:** Bash, `python3`, coreutils (`mktemp`, `mv`, `dirname`, `pwd`).

---

### Task 1: Add Failing Tests for Result File Path and JSON Serialization

**Files:**
- Modify: `scripts/tests/gh_proxy_bench_test.sh`
- Test: `scripts/tests/gh_proxy_bench_test.sh`

- [ ] **Step 1: Add a failing test for the fixed output path**

```bash
test_result_file_path() {
  local path
  path="$(result_file_path)"
  assert_eq \
    "$ROOT_DIR/scripts/gh_proxy_bench_result.json" \
    "$path" \
    "result_file_path should resolve the fixed JSON output next to the script"
}
```

- [ ] **Step 2: Add a failing test for JSON serialization**

```bash
test_render_json_report() {
  local success_rows failure_rows output
  success_rows=$'默认节点\tgh.llkk.cc\t200\t0.123\t0.456\thttps://gh.llkk.cc/example'
  failure_rows=$'ghpr.cc\thttp_404'
  output="$(render_json_report "2026-05-12T18:00:00+08:00" "$success_rows" "$failure_rows")"

  [[ "$output" == *'"target_url": "https://github.com/har01d5/tvbox/raw/refs/heads/master/spiders_v2.json"'* ]] || {
    printf 'ASSERT FAIL: render_json_report should include target_url\n' >&2
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
```

- [ ] **Step 3: Register the new tests**

```bash
test_result_file_path
test_render_json_report
```

- [ ] **Step 4: Run the test harness to verify it fails**

Run: `bash scripts/tests/gh_proxy_bench_test.sh`
Expected: FAIL because `result_file_path` and `render_json_report` do not exist yet.

- [ ] **Step 5: Commit**

```bash
git add scripts/tests/gh_proxy_bench_test.sh
git commit -m "test: add gh proxy result file expectations"
```

### Task 2: Implement JSON Report Generation and Atomic File Write

**Files:**
- Modify: `scripts/gh_proxy_bench.sh`
- Modify: `scripts/tests/gh_proxy_bench_test.sh`

- [ ] **Step 1: Add the path resolver and serializer**

```bash
script_dir() {
  cd "$(dirname "${BASH_SOURCE[0]}")" && pwd
}

result_file_path() {
  printf '%s/gh_proxy_bench_result.json\n' "$(script_dir)"
}

render_json_report() {
  local generated_at="$1"
  local success_rows="$2"
  local failure_rows="$3"

  python3 - "$generated_at" "$GH_PROXY_TARGET_URL" "$GH_PROXY_API_URL" "$success_rows" "$failure_rows" <<'PY'
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
```

- [ ] **Step 2: Add the atomic writer**

```bash
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
```

- [ ] **Step 3: Call the writer at the end of `main()`**

```bash
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bash -n scripts/gh_proxy_bench.sh && bash scripts/tests/gh_proxy_bench_test.sh`
Expected: PASS with `gh_proxy_bench tests: PASS`

- [ ] **Step 5: Commit**

```bash
git add scripts/gh_proxy_bench.sh scripts/tests/gh_proxy_bench_test.sh
git commit -m "feat: persist gh proxy benchmark results"
```

### Task 3: Verify Fixed-Path Overwrite Behavior End to End

**Files:**
- Modify: `scripts/gh_proxy_bench.sh` (only if verification exposes a bug)
- Test: `scripts/gh_proxy_bench.sh`

- [ ] **Step 1: Run the benchmark script once**

Run: `bash scripts/gh_proxy_bench.sh`
Expected:
- existing terminal table still prints
- `scripts/gh_proxy_bench_result.json` is created

- [ ] **Step 2: Validate the JSON file**

Run: `python3 -m json.tool scripts/gh_proxy_bench_result.json >/tmp/gh_proxy_bench_result.pretty.json`
Expected: command exits 0

- [ ] **Step 3: Confirm the JSON contains the expected top-level keys**

Run: `python3 - <<'PY'
import json
from pathlib import Path
data = json.loads(Path("scripts/gh_proxy_bench_result.json").read_text())
assert "generated_at" in data
assert "target_url" in data
assert "discovery_api" in data
assert "success_nodes" in data
assert "failed_nodes" in data
print("json keys: PASS")
PY`
Expected: prints `json keys: PASS`

- [ ] **Step 4: Confirm the file is overwritten in place**

Run: `bash scripts/gh_proxy_bench.sh && test -f scripts/gh_proxy_bench_result.json && echo overwrite-check: PASS`
Expected: prints `overwrite-check: PASS` and no new timestamped result files appear in `scripts/`

- [ ] **Step 5: Commit if verification required a fix**

```bash
git add scripts/gh_proxy_bench.sh scripts/tests/gh_proxy_bench_test.sh
git commit -m "fix: finalize gh proxy result file output"
```

If no verification fix was needed, skip this step.
