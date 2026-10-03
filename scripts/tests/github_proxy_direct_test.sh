#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

assert_eq() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  if [[ "$expected" != "$actual" ]]; then
    printf 'ASSERT FAIL: %s\nexpected: [%s]\nactual:   [%s]\n' "$message" "$expected" "$actual" >&2
    exit 1
  fi
}

assert_not_contains() {
  local file="$1"
  local pattern="$2"
  if grep -Fq "$pattern" "$file"; then
    printf 'ASSERT FAIL: %s should not contain [%s]\n' "$file" "$pattern" >&2
    exit 1
  fi
}

assert_contains() {
  local file="$1"
  local pattern="$2"
  if ! grep -Fq "$pattern" "$file"; then
    printf 'ASSERT FAIL: %s should contain [%s]\n' "$file" "$pattern" >&2
    exit 1
  fi
}

extract_download_function() {
  local script="$1"
  local proxy_file="$2"
  awk '/^download_with_proxy\(\) \{/,/^}/' "$script" | sed "s#/data/github_proxy.txt#$proxy_file#g"
}

# mock wget 记录实际收到的 URL;仅当 URL 等于 $WGET_SUCCESS_URL(缺省=直连地址)时返回成功
install_mock_wget() {
  local dir="$1"
  cat >"$dir/wget" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail

url=""
output=""
prev=""
for arg in "$@"; do
  if [[ "$prev" == "-O" ]]; then
    output="$arg"
  fi
  if [[ "$arg" == http* ]]; then
    url="$arg"
  fi
  prev="$arg"
done

printf '%s\n' "$url" >>"$WGET_LOG"

success_url="${WGET_SUCCESS_URL:-https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt}"
if [[ "$url" == "$success_url" ]]; then
  printf 'ok\n' >"$output"
  exit 0
fi

exit 1
MOCK
  chmod +x "$dir/wget"
}

run_download_function() {
  local script="$1"
  local proxy_content="$2"
  local tmp proxy_file mockbin lib output
  tmp="$(mktemp -d)"
  proxy_file="$tmp/github_proxy.txt"
  mockbin="$tmp/bin"
  lib="$tmp/download.sh"
  output="$tmp/version.txt"
  mkdir -p "$mockbin"
  printf '%s' "$proxy_content" >"$proxy_file"
  extract_download_function "$script" "$proxy_file" >"$lib"
  install_mock_wget "$mockbin"

  (
    export PATH="$mockbin:$PATH"
    export WGET_LOG="$tmp/wget.log"
    # shellcheck source=/dev/null
    source "$lib"
    # docker/scripts/lib/download.sh 依赖 lib/common.sh 的日志函数,沙箱里置空
    log_info() { :; }
    log_warn() { :; }
    log_error() { :; }
    download_with_proxy "https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt" "$output"
  )

  head -n 1 "$tmp/wget.log"
}

test_download_function_preserves_direct_entry_order() {
  local first_url
  first_url="$(run_download_function "$1" '
https://gh.llkk.cc/
')"
  assert_eq \
    "https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt" \
    "$first_url" \
    "$1 should preserve an empty first proxy entry as direct download"
}

# github_proxy.txt 里的代理行常不带尾斜杠,裸拼接会产出 https://gh.llkk.https://... 这类坏 URL
# (wget 报 bad port),必须归一化补上斜杠
test_download_function_appends_missing_trailing_slash() {
  local first_url
  first_url="$(WGET_SUCCESS_URL="https://gh.llkk.cc/https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt" \
    run_download_function "$1" 'https://gh.llkk.cc
')"
  assert_eq \
    "https://gh.llkk.cc/https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt" \
    "$first_url" \
    "$1 should append a trailing slash to proxy entries without one"
}

test_no_script_drops_blank_proxy_entries() {
  assert_not_contains "$ROOT_DIR/docker/scripts/lib/download.sh" "grep -v '^$'"
  assert_not_contains "$ROOT_DIR/scripts/sync.sh" "grep -v '^$'"
  assert_not_contains "$ROOT_DIR/scripts/index.sh" "grep -v '^$'"
  assert_not_contains "$ROOT_DIR/scripts/init.sh" "grep -v '^$'"
}

# 所有代理消费方必须归一化尾斜杠:index.sh/init.sh 是内联循环,不会被上面的函数级用例覆盖
test_all_proxy_consumers_normalize_trailing_slash() {
  local normalize='case "$proxy" in */) ;; *) proxy="${proxy}/" ;; esac'
  assert_contains "$ROOT_DIR/docker/scripts/lib/download.sh" "$normalize"
  assert_contains "$ROOT_DIR/scripts/sync.sh" "$normalize"
  assert_contains "$ROOT_DIR/scripts/index.sh" "$normalize"
  assert_contains "$ROOT_DIR/scripts/init.sh" "$normalize"
}

for script in "$ROOT_DIR/docker/scripts/lib/download.sh" "$ROOT_DIR/scripts/sync.sh"; do
  test_download_function_preserves_direct_entry_order "$script"
  test_download_function_appends_missing_trailing_slash "$script"
done
test_no_script_drops_blank_proxy_entries
test_all_proxy_consumers_normalize_trailing_slash

printf 'github proxy direct tests: PASS\n'
