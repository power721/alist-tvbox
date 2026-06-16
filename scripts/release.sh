#!/usr/bin/env bash
set -euo pipefail

die() {
  printf '%s\n' "$1" >&2
  exit 1
}

require_clean_release_notes_only() {
  local line status path normalized
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    status="${line:0:2}"
    path="${line:3}"
    normalized="${path#./}"
    [[ "$normalized" == "RELEASE_NOTES.md" ]] && continue
    [[ "$status" == "??" && "$normalized" == docs/* ]] && continue
    die "RELEASE_NOTES.md 之外存在未提交改动: $normalized"
  done < <(git status --porcelain)
}

release_page_url() {
  local tag="$1" remote_url repo

  remote_url="$(git remote get-url origin 2>/dev/null || true)"
  case "$remote_url" in
    git@github.com:*)
      repo="${remote_url#git@github.com:}"
      repo="${repo%.git}"
      ;;
    https://github.com/*)
      repo="${remote_url#https://github.com/}"
      repo="${repo%.git}"
      ;;
    ssh://git@github.com/*)
      repo="${remote_url#ssh://git@github.com/}"
      repo="${repo%.git}"
      ;;
    *)
      repo=""
      ;;
  esac

  if [[ -n "$repo" ]]; then
    printf 'https://github.com/%s/releases/tag/%s\n' "$repo" "$tag"
    return
  fi

  printf '%s\n' "$tag"
}

version="${1:-}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "版本号必须是 X.Y.Z"

tag="$version"
[[ -s RELEASE_NOTES.md ]] || die "RELEASE_NOTES.md 不能为空"

branch="$(git rev-parse --abbrev-ref HEAD)"
head_sha="$(git rev-parse HEAD)"
printf 'branch=%s\nHEAD=%s\nversion=%s\ntag=%s\n' "$branch" "$head_sha" "$version" "$tag"

[[ "$branch" == "main" || "$branch" == "master" ]] || die "只能在 main/master 分支执行发布脚本"
require_clean_release_notes_only

git fetch origin main --tags
read -r behind ahead < <(git rev-list --left-right --count origin/main...HEAD)
[[ "$behind" == "0" ]] || die "当前分支落后远端，请先同步 origin/main"

[[ -z "$(git tag --list "$tag")" ]] || die "本地已存在 tag: $tag"
[[ -z "$(git ls-remote --tags origin "$tag")" ]] || die "远端已存在 tag: $tag"

git add RELEASE_NOTES.md
git commit -m "docs: add release notes for $tag"
git push origin "$branch"
git tag "$tag"
git push origin "$tag"

release_url="$(gh release view "$tag" --json url --jq .url 2>/dev/null || true)"
[[ -n "$release_url" ]] || release_url="$(release_page_url "$tag")"
printf '%s\n' "$release_url"
