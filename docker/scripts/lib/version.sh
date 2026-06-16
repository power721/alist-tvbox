#!/bin/sh
# 版本管理工具

# 比较版本号（返回0表示相等，1表示需要更新，2表示本地更新）
compare_version() {
  local_ver="$1"
  remote_ver="$2"

  if [ "$local_ver" = "$remote_ver" ]; then
    return 0
  fi

  # 检查 sort 是否支持 -V（版本号排序）
  if echo "1.0.0" | sort -V >/dev/null 2>&1; then
    latest=$(printf "%s\n%s\n" "$remote_ver" "$local_ver" | sort -V | tail -n1)
  else
    # Fallback: 使用简单字符串比较
    log_warn "Version-aware sort not available, using string comparison"
    latest=$(printf "%s\n%s\n" "$remote_ver" "$local_ver" | sort | tail -n1)
  fi

  if [ "$remote_ver" = "$latest" ]; then
    return 1  # 远程更新
  else
    return 2  # 本地更新
  fi
}

# 读取版本文件
read_version() {
  version_file="$1"
  if [ -f "$version_file" ]; then
    head -n1 "$version_file"
  else
    echo "0.0.0"
  fi
}

# 检查是否已初始化
is_initialized() {
  [ -f "/opt/alist/data/.init" ] && [ "$(head -n1 /opt/alist/data/.init)" = "1" ]
}

# 标记为已初始化
mark_initialized() {
  echo "1" > /opt/alist/data/.init
  log_info "Marked as initialized"
}
