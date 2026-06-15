#!/bin/sh
# 版本管理工具

# 比较版本号（返回0表示相等，1表示需要更新，2表示本地更新）
compare_version() {
  local_ver="$1"
  remote_ver="$2"

  if [ "$local_ver" = "$remote_ver" ]; then
    return 0
  fi

  # 使用 sort 判断哪个版本更新
  latest=$(printf "%s\n%s\n" "$remote_ver" "$local_ver" | sort -V | tail -n1)

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
