# GitHub Proxy Fallback Pool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand `scripts/gh_proxy_bench.sh` so discovery failures fall back to a fixed pool of six built-in proxy domains instead of only `gh.llkk.cc`.

**Architecture:** Keep API discovery untouched as the primary path and isolate the new behavior in `fallback_nodes()`. Use lightweight Bash tests to assert the full fallback pool contents and labels, then leave the rest of the benchmark flow unchanged so runtime ranking, terminal output, and JSON output continue to work automatically.

**Tech Stack:** Bash, existing shell test harness, coreutils.

---

### Task 1: Add Failing Tests for the Expanded Fallback Pool

**Files:**
- Modify: `scripts/tests/gh_proxy_bench_test.sh`
- Test: `scripts/tests/gh_proxy_bench_test.sh`

- [ ] **Step 1: Replace the single-host fallback expectation with the full pool**

```bash
test_fallback_nodes() {
  local actual expected
  actual="$(fallback_nodes)"
  expected=$'默认节点\tgh.llkk.cc\n备用节点\tgh-proxy.org\n备用节点\thk.gh-proxy.org\n备用节点\tcdn.gh-proxy.org\n备用节点\tedgeone.gh-proxy.org\n备用节点\tgh.felicity.ac.cn'
  assert_eq "$expected" "$actual" "fallback_nodes should expose the full built-in fallback pool"
}
```

- [ ] **Step 2: Add a discovery-failure regression test**

```bash
test_discover_nodes_falls_back_to_pool() {
  local actual expected
  curl() {
    return 22
  }

  actual="$(discover_nodes)"
  expected=$'默认节点\tgh.llkk.cc\n备用节点\tgh-proxy.org\n备用节点\thk.gh-proxy.org\n备用节点\tcdn.gh-proxy.org\n备用节点\tedgeone.gh-proxy.org\n备用节点\tgh.felicity.ac.cn'
  assert_eq "$expected" "$actual" "discover_nodes should return the full fallback pool when API discovery fails"
}
```

- [ ] **Step 3: Register the new regression test**

```bash
test_discover_nodes_falls_back_to_pool
```

- [ ] **Step 4: Run the test harness to verify it fails**

Run: `bash scripts/tests/gh_proxy_bench_test.sh`
Expected: FAIL because `fallback_nodes()` still only returns `gh.llkk.cc`.

- [ ] **Step 5: Commit**

```bash
git add scripts/tests/gh_proxy_bench_test.sh
git commit -m "test: add gh proxy fallback pool expectations"
```

### Task 2: Implement the Built-In Fallback Pool

**Files:**
- Modify: `scripts/gh_proxy_bench.sh`
- Modify: `scripts/tests/gh_proxy_bench_test.sh`

- [ ] **Step 1: Replace the current one-host fallback implementation**

```bash
fallback_nodes() {
  printf '%s\n' \
    $'默认节点\tgh.llkk.cc' \
    $'备用节点\tgh-proxy.org' \
    $'备用节点\thk.gh-proxy.org' \
    $'备用节点\tcdn.gh-proxy.org' \
    $'备用节点\tedgeone.gh-proxy.org' \
    $'备用节点\tgh.felicity.ac.cn'
}
```

- [ ] **Step 2: Keep `discover_nodes()` delegating to `fallback_nodes()` without further flow changes**

```bash
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

- [ ] **Step 3: Run tests to verify they pass**

Run: `bash -n scripts/gh_proxy_bench.sh && bash scripts/tests/gh_proxy_bench_test.sh`
Expected: PASS with `gh_proxy_bench tests: PASS`

- [ ] **Step 4: Run a controlled fallback-path execution**

Run: `GH_PROXY_BENCH_SOURCE_ONLY=1 bash -lc 'source scripts/gh_proxy_bench.sh; curl() { return 22; }; discover_nodes'`
Expected:
- prints all 6 fallback hosts
- first row is `默认节点    gh.llkk.cc`
- remaining rows are labeled `备用节点`

- [ ] **Step 5: Commit**

```bash
git add scripts/gh_proxy_bench.sh scripts/tests/gh_proxy_bench_test.sh
git commit -m "feat: add gh proxy fallback pool"
```

### Task 3: Verify Runtime Benchmark and Result File Still Work with Fallback

**Files:**
- Modify: `scripts/gh_proxy_bench.sh` only if verification exposes a bug

- [ ] **Step 1: Run the benchmark script normally**

Run: `bash scripts/gh_proxy_bench.sh`
Expected:
- if API discovery works, normal dynamic behavior continues
- if API discovery fails, the script benchmarks the full fallback pool instead of only `gh.llkk.cc`

- [ ] **Step 2: Validate that the fixed JSON result file is still produced**

Run: `python3 -m json.tool scripts/gh_proxy_bench_result.json >/tmp/gh_proxy_bench_result.pretty.json`
Expected: exits 0

- [ ] **Step 3: Confirm fallback hosts can appear in the JSON output**

Run: `python3 - <<'PY'
import json
from pathlib import Path
data = json.loads(Path('scripts/gh_proxy_bench_result.json').read_text())
hosts = {node['host'] for node in data.get('success_nodes', [])}
hosts |= {node['host'] for node in data.get('failed_nodes', [])}
assert 'gh.llkk.cc' in hosts
print('fallback json hosts: PASS')
PY`
Expected: prints `fallback json hosts: PASS`

- [ ] **Step 4: Commit if verification required a fix**

```bash
git add scripts/gh_proxy_bench.sh scripts/tests/gh_proxy_bench_test.sh
git commit -m "fix: finalize gh proxy fallback pool"
```

If no verification fix was needed, skip this step.
