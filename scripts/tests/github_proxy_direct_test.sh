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

extract_download_function() {
  local script="$1"
  local proxy_file="$2"
  awk '/^download_with_proxy\(\) \{/,/^}/' "$script" | sed "s#/data/github_proxy.txt#$proxy_file#g"
}

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

if [[ "$url" == "https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt" ]]; then
  printf 'ok\n' >"$output"
  exit 0
fi

exit 1
MOCK
  chmod +x "$dir/wget"
}

test_download_function_preserves_direct_entry_order() {
  local script="$1"
  local tmp proxy_file mockbin lib output first_url
  tmp="$(mktemp -d)"
  proxy_file="$tmp/github_proxy.txt"
  mockbin="$tmp/bin"
  lib="$tmp/download.sh"
  output="$tmp/version.txt"
  mkdir -p "$mockbin"
  printf '\nhttps://gh.llkk.cc/\n' >"$proxy_file"
  extract_download_function "$script" "$proxy_file" >"$lib"
  install_mock_wget "$mockbin"

  (
    export PATH="$mockbin:$PATH"
    export WGET_LOG="$tmp/wget.log"
    # shellcheck source=/dev/null
    source "$lib"
    download_with_proxy "https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt" "$output"
  )

  first_url="$(head -n 1 "$tmp/wget.log")"
  assert_eq \
    "https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt" \
    "$first_url" \
    "$script should preserve an empty first proxy entry as direct download"
}

test_no_script_drops_blank_proxy_entries() {
  assert_not_contains "$ROOT_DIR/init.sh" "grep -v '^$'"
  assert_not_contains "$ROOT_DIR/scripts/sync.sh" "grep -v '^$'"
  assert_not_contains "$ROOT_DIR/scripts/index.sh" "grep -v '^$'"
  assert_not_contains "$ROOT_DIR/scripts/init.sh" "grep -v '^$'"
}

test_download_function_preserves_direct_entry_order "$ROOT_DIR/init.sh"
test_download_function_preserves_direct_entry_order "$ROOT_DIR/scripts/sync.sh"
test_no_script_drops_blank_proxy_entries

printf 'github proxy direct tests: PASS\n'
