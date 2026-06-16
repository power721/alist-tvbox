#!/bin/sh
# 公共函数库 - 日志和错误处理

# 日志输出
log_info() {
  echo "[INFO] $(date '+%Y-%m-%d %H:%M:%S') $*"
}

log_warn() {
  echo "[WARN] $(date '+%Y-%m-%d %H:%M:%S') $*" >&2
}

log_error() {
  echo "[ERROR] $(date '+%Y-%m-%d %H:%M:%S') $*" >&2
}

# 检查命令是否成功
check_success() {
  if [ $? -ne 0 ]; then
    log_error "$1"
    return 1
  fi
  return 0
}

# 确保目录存在
ensure_dir() {
  for dir in "$@"; do
    if [ ! -d "$dir" ]; then
      mkdir -p "$dir"
      log_info "Created directory: $dir"
    fi
  done
}

# 安全的符号链接（删除旧的再创建）
safe_symlink() {
  src="$1"
  dest="$2"
  [ -e "$dest" ] && rm -rf "$dest"
  ln -sf "$src" "$dest"
  log_info "Created symlink: $dest -> $src"
}
