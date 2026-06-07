#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HELPER="$ROOT_DIR/scripts/tg-provider-build-cache.sh"

assert_eq() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  if [[ "$expected" != "$actual" ]]; then
    printf 'ASSERT FAIL: %s\nexpected: [%s]\nactual:   [%s]\n' "$message" "$expected" "$actual" >&2
    exit 1
  fi
}

assert_file_exists() {
  local path="$1"
  local message="$2"
  if [[ ! -f "$path" ]]; then
    printf 'ASSERT FAIL: %s\nmissing file: %s\n' "$message" "$path" >&2
    exit 1
  fi
}

assert_script_prepares_provider_before_docker_build() {
  local script="$1"
  local dockerfile="$2"
  local script_path="$ROOT_DIR/$script"
  local prepare_line docker_line

  grep -q 'tg-provider-build-cache.sh' "$script_path" || {
    printf 'ASSERT FAIL: %s should source tg-provider-build-cache.sh\n' "$script" >&2
    exit 1
  }

  prepare_line="$(grep -n 'prepare_tg_provider' "$script_path" | tail -n 1 | cut -d: -f1)"
  docker_line="$(grep -n "docker build -f docker/$dockerfile" "$script_path" | head -n 1 | cut -d: -f1)"

  if [[ -z "$prepare_line" || -z "$docker_line" || "$prepare_line" -ge "$docker_line" ]]; then
    printf 'ASSERT FAIL: %s should call prepare_tg_provider before docker/%s build\n' "$script" "$dockerfile" >&2
    exit 1
  fi
}

if [[ ! -f "$HELPER" ]]; then
  printf 'ASSERT FAIL: scripts/tg-provider-build-cache.sh should provide shared tg-provider caching\n' >&2
  exit 1
fi

# shellcheck source=/dev/null
source "$HELPER"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

export TG_PROVIDER_BUILD_DIR="$tmpdir/tg-provider"
export TG_PROVIDER_RELEASE_LATEST_URL="https://github.com/power721/telegram-search/releases/latest"
tag_file="$tmpdir/latest-tag"
download_log="$tmpdir/download.log"
printf 'v1.0.0' > "$tag_file"

curl() {
  local output=""
  local write_effective=false
  local url=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      -o)
        output="$2"
        shift 2
        ;;
      -w)
        if [[ "$2" == "%{url_effective}" ]]; then
          write_effective=true
        fi
        shift 2
        ;;
      -*)
        shift
        ;;
      *)
        url="$1"
        shift
        ;;
    esac
  done

  if [[ "$write_effective" == true ]]; then
    printf 'https://github.com/power721/telegram-search/releases/tag/%s' "$(cat "$tag_file")"
    return 0
  fi

  mkdir -p "$(dirname "$output")"
  case "$url" in
    */tg-provider-linux-amd64)
      printf 'amd64-%s\n' "$(cat "$tag_file")" > "$output"
      printf '%s\n' "$url" >> "$download_log"
      ;;
    */tg-provider-linux-arm64)
      printf 'arm64-%s\n' "$(cat "$tag_file")" > "$output"
      printf '%s\n' "$url" >> "$download_log"
      ;;
    */checksums.txt)
      local tag
      tag="$(cat "$tag_file")"
      {
        printf 'amd64-%s\n' "$tag" | sha256sum | awk '{print $1 "  tg-provider-linux-amd64"}'
        printf 'arm64-%s\n' "$tag" | sha256sum | awk '{print $1 "  tg-provider-linux-arm64"}'
      } > "$output"
      printf '%s\n' "$url" >> "$download_log"
      ;;
    *)
      printf 'unexpected curl url: %s\n' "$url" >&2
      return 1
      ;;
  esac
}

prepare_tg_provider

assert_eq "v1.0.0" "$(cat "$TG_PROVIDER_BUILD_DIR/version")" "first run should store latest tg-provider version"
assert_file_exists "$TG_PROVIDER_BUILD_DIR/linux-amd64/tg-provider" "first run should install amd64 provider"
assert_file_exists "$TG_PROVIDER_BUILD_DIR/linux-arm64/tg-provider" "first run should install arm64 provider"
assert_eq "3" "$(wc -l < "$download_log" | tr -d ' ')" "first run should download two binaries and checksums"

prepare_tg_provider
assert_eq "3" "$(wc -l < "$download_log" | tr -d ' ')" "same complete version should not redownload assets"

rm "$TG_PROVIDER_BUILD_DIR/linux-amd64/tg-provider"
prepare_tg_provider
assert_file_exists "$TG_PROVIDER_BUILD_DIR/linux-amd64/tg-provider" "missing cached binary should be restored"
assert_eq "6" "$(wc -l < "$download_log" | tr -d ' ')" "missing binary should force a release asset refresh"

printf 'v1.1.0' > "$tag_file"
prepare_tg_provider
assert_eq "v1.1.0" "$(cat "$TG_PROVIDER_BUILD_DIR/version")" "new latest version should update cached version"
assert_eq "amd64-v1.1.0" "$(tr -d '\n' < "$TG_PROVIDER_BUILD_DIR/linux-amd64/tg-provider")" "new latest version should replace amd64 provider"
assert_eq "9" "$(wc -l < "$download_log" | tr -d ' ')" "new latest version should redownload release assets"

printf 'tg-provider build cache tests: PASS\n'

assert_script_prepares_provider_before_docker_build "build-xiaoya.sh" "Dockerfile-xiaoya"
assert_script_prepares_provider_before_docker_build "build-native.sh" "Dockerfile-native"
assert_script_prepares_provider_before_docker_build "build-hostmode.sh" "Dockerfile-host"
assert_script_prepares_provider_before_docker_build "build-docker.sh" "Dockerfile"

printf 'local build tg-provider integration tests: PASS\n'
