#!/usr/bin/env bash

TG_PROVIDER_RELEASE_LATEST_URL="${TG_PROVIDER_RELEASE_LATEST_URL:-https://github.com/power721/telegram-search/releases/latest}"
TG_PROVIDER_BUILD_DIR="${TG_PROVIDER_BUILD_DIR:-build/tg-provider}"

tg_provider_latest_tag() {
  local effective_url tag
  effective_url="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "$TG_PROVIDER_RELEASE_LATEST_URL")"
  tag="${effective_url##*/}"
  if [ -z "$tag" ] || [ "$tag" = "latest" ]; then
    echo "failed to resolve latest tg-provider release from $TG_PROVIDER_RELEASE_LATEST_URL" >&2
    return 1
  fi
  printf '%s\n' "$tag"
}

tg_provider_cache_complete() {
  local tag="$1"
  [ -f "$TG_PROVIDER_BUILD_DIR/version" ] || return 1
  [ "$(cat "$TG_PROVIDER_BUILD_DIR/version")" = "$tag" ] || return 1
  [ -x "$TG_PROVIDER_BUILD_DIR/linux-amd64/tg-provider" ] || return 1
  [ -x "$TG_PROVIDER_BUILD_DIR/linux-arm64/tg-provider" ] || return 1
}

download_tg_provider_release() {
  local tag="$1"
  local download_dir="$TG_PROVIDER_BUILD_DIR/download"
  local release_url="https://github.com/power721/telegram-search/releases/download/$tag"

  rm -rf "$download_dir"
  mkdir -p "$download_dir" "$TG_PROVIDER_BUILD_DIR/linux-amd64" "$TG_PROVIDER_BUILD_DIR/linux-arm64"

  echo "=== download tg-provider $tag ==="
  curl -fsSL -o "$download_dir/tg-provider-linux-amd64" "$release_url/tg-provider-linux-amd64"
  curl -fsSL -o "$download_dir/tg-provider-linux-arm64" "$release_url/tg-provider-linux-arm64"
  curl -fsSL -o "$download_dir/checksums.txt" "$release_url/checksums.txt"

  (
    cd "$download_dir"
    sha256sum -c checksums.txt
  )

  install -m 0755 "$download_dir/tg-provider-linux-amd64" "$TG_PROVIDER_BUILD_DIR/linux-amd64/tg-provider"
  install -m 0755 "$download_dir/tg-provider-linux-arm64" "$TG_PROVIDER_BUILD_DIR/linux-arm64/tg-provider"
  printf '%s\n' "$tag" > "$TG_PROVIDER_BUILD_DIR/version"
}

prepare_tg_provider() {
  local tag
  tag="$(tg_provider_latest_tag)"
  if tg_provider_cache_complete "$tag"; then
    echo "=== tg-provider $tag already cached ==="
    return 0
  fi
  download_tg_provider_release "$tag"
  echo "tg-provider release: $(cat "$TG_PROVIDER_BUILD_DIR/version")"
  ls -l "$TG_PROVIDER_BUILD_DIR/linux-amd64/tg-provider" "$TG_PROVIDER_BUILD_DIR/linux-arm64/tg-provider"
}
