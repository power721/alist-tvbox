#!/bin/sh
# 下载工具 - 支持多代理 fallback

# 通用下载函数，支持多代理 fallback
download_with_proxy() {
  url=$1
  output=$2

  # 尝试使用 github_proxy.txt 中的代理
  if [ -f "/data/github_proxy.txt" ]; then
    proxy_count=0
    while IFS= read -r proxy || [ -n "$proxy" ]; do
      proxy_count=$((proxy_count + 1))
      [ "$proxy_count" -gt 5 ] && break

      if [ -n "$proxy" ]; then
        candidate="${proxy}${url}"
        log_info "Trying proxy: $proxy"
      else
        candidate="${url}"
        log_info "Trying direct connection"
      fi

      if wget -T 30 -t 1 "${candidate}" -O "${output}" 2>/dev/null; then
        log_info "Downloaded successfully: $output"
        return 0
      else
        rm -f "${output}"  # Clean up partial downloads
      fi
    done < "/data/github_proxy.txt"
  fi

  # 所有代理失败，尝试直连
  log_warn "All proxies failed, trying direct connection"
  if wget -T 30 -t 2 "${url}" -O "${output}" 2>/dev/null; then
    log_info "Downloaded successfully (direct): $output"
    return 0
  fi

  log_error "Failed to download: $url"
  rm -f "${output}"  # Clean up partial downloads
  return 1
}

# 下载并解压 zip 文件
download_and_extract_zip() {
  url=$1
  output_zip=$2
  extract_dir=$3
  fallback_zip=${4:-""}

  # 尝试下载
  if ! download_with_proxy "$url" "$output_zip"; then
    # 使用本地备份
    if [ -n "$fallback_zip" ] && [ -f "$fallback_zip" ]; then
      log_warn "Using local fallback: $fallback_zip"
      cp "$fallback_zip" "$output_zip"
    else
      return 1
    fi
  fi

  # 解压
  if [ -f "$output_zip" ]; then
    unzip -q -o "$output_zip" -d "$extract_dir"
    check_success "Failed to extract: $output_zip" || return 1
    log_info "Extracted: $output_zip -> $extract_dir"
    return 0
  fi

  return 1
}
