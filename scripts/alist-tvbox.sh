#!/usr/bin/env bash
set -euo pipefail

# 脚本版本
SCRIPT_VERSION="4.0.0"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m' # No Color

# 版本定义
declare -A VERSIONS=(
  ["1"]="haroldli/alist-tvbox               - 纯净版（推荐）"
  ["2"]="haroldli/alist-tvbox:native        - 纯净原生版"
  ["3"]="haroldli/alist-tvbox:python        - 纯净版（Python运行环境）"
  ["4"]="haroldli/xiaoya-tvbox              - 小雅集成版（推荐）"
  ["5"]="haroldli/xiaoya-tvbox:native       - 小雅原生版"
  ["6"]="haroldli/xiaoya-tvbox:native-host  - 小雅原生主机版"
  ["7"]="haroldli/xiaoya-tvbox:host         - 小雅主机模式版"
  ["8"]="haroldli/xiaoya-tvbox:hostmode-dev - 开发测试版host网络"
  ["9"]="haroldli/xiaoya-tvbox:dev          - 开发测试版"
)

# 默认配置
CONFIG_FILE="$HOME/.config/alist-tvbox/app.conf"

detect_best_base_dir() {
  local best_path="/opt/alist-tvbox"
  local best_size=0

  local candidates=(
    "/volume1/docker/alist-tvbox"
    "/volume2/docker/alist-tvbox"
    "/volume3/docker/alist-tvbox"
    "/share/CACHEDEV1_DATA/docker/alist-tvbox"
    "/share/CACHEDEV2_DATA/docker/alist-tvbox"
  )

  for p in "${candidates[@]}"; do
    local base
    base="$(dirname "$p")"
    if [[ -d "$base" ]]; then
      local avail
      avail="$(df -P "$base" 2>/dev/null | awk 'NR==2 {print $4}')"
      if [[ -n "$avail" && "$avail" =~ ^[0-9]+$ && "$avail" -gt "$best_size" ]]; then
        best_size="$avail"
        best_path="$p"
      fi
    fi
  done

  echo "$best_path"
}
detect_base_dir() {
    if [[ -f "/proc/sys/kernel/syno_hw_version" ]]; then
        echo "/volume1/docker/alist-tvbox"
    else
        echo "/opt/alist-tvbox"
    fi
}

# 初始化基础目录


DEFAULT_BASE_DIR=$(detect_base_dir)

declare -A DEFAULT_CONFIG=(
  ["MODE"]="docker"
  ["IMAGE_ID"]="4"
  ["IMAGE_NAME"]="haroldli/xiaoya-tvbox"
  ["BASE_DIR"]="$DEFAULT_BASE_DIR"
  ["PORT1"]="4567"
  ["PORT2"]="5344"
  ["NETWORK"]="bridge"
  ["RESTART"]="always"
  ["MOUNT_WWW"]="false"
  ["GITHUB_PROXY"]=""
)

# 初始化配置字典
declare -A CONFIG
for key in "${!DEFAULT_CONFIG[@]}"; do
  CONFIG["$key"]="${DEFAULT_CONFIG[$key]}"
done

is_nas() {
    # 检查是否为群晖 DSM
    if [[ -f "/proc/sys/kernel/syno_hw_version" ]]; then
        echo -e "${GREEN}当前运行在群晖(Synology) NAS上${NC}"
        return 0
    fi

    # 检查其他 NAS 系统（如 QNAP、TrueNAS 等）
    if grep -q "Synology" /etc/os-release 2>/dev/null || \
       grep -q "QNAP" /etc/os-release 2>/dev/null || \
       grep -q "TrueNAS" /etc/os-release 2>/dev/null; then
        local nas_type=$(grep -E "Synology|QNAP|TrueNAS" /etc/os-release | head -1 | cut -d'=' -f2 | tr -d '"')
        echo -e "${GREEN}当前运行在 ${nas_type} NAS上${NC}"
        return 0
    fi

    echo -e "${YELLOW}当前系统不是已知的 NAS 设备${NC}"
    return 1
}

is_synology_nas() {
    if uname -a | grep -iq "synology" || \
       ps aux | grep -q "[s]ynoservice" || \
       [[ -f "/etc.defaults/VERSION" ]]; then
        echo -e "${GREEN}当前运行在群晖(Synology) NAS上${NC}"
        return 0
    else
        echo -e "${YELLOW}当前系统不是群晖 NAS${NC}"
        return 1
    fi
}

is_nas_storage() {
    if lsblk -o NAME,FSTYPE | grep -q "btrfs" || \
       [[ $(df -T / | awk 'NR==2 {print $2}') == "btrfs" ]] || \
       [[ -f "/proc/mdstat" && $(grep -c "active raid" /proc/mdstat) -gt 0 ]]; then
        echo -e "${GREEN}检测到 NAS 常用的存储管理方式（Btrfs/RAID）${NC}"
        return 0
    else
        echo -e "${YELLOW}未检测到典型的 NAS 存储配置${NC}"
        return 1
    fi
}

check_nas_environment() {
    echo -e "${CYAN}正在检测系统是否为 NAS...${NC}"

    if is_nas || is_synology_nas || is_nas_storage; then
        echo -e "${GREEN}✓ 当前运行环境是 NAS${NC}"
        return 0
    else
        echo -e "${YELLOW}× 当前环境不是典型的 NAS 设备${NC}"
        return 1
    fi
}

# 检测运行环境
check_environment() {
  echo -e "${CYAN}正在检测运行环境...${NC}"

  # 1. 检查Docker是否安装
  if ! command -v docker &>/dev/null; then
    echo -e "${RED}错误：Docker未安装！${NC}"
    exit 1
  fi

  # 2. 检查Docker服务状态
  if ! docker info &>/dev/null; then
    echo -e "${RED}错误：无法连接 Docker 服务！${NC}"
    echo -e "${YELLOW}请确保："
    echo -e "1. Docker 已安装并运行"
    echo -e "2. 当前用户已加入 'docker' 组${NC}"
    exit 1
  fi

  # 3. 检查镜像加速配置（不再强制检测Docker Hub连通性）
  local using_mirror=false
  if [[ -f "/etc/docker/daemon.json" ]] &&
     grep -q "registry-mirrors" /etc/docker/daemon.json; then
    using_mirror=true
  fi

  # 4. 仅提示未配置镜像加速的情况
  if [[ "$using_mirror" == "false" ]]; then
    echo -e "${YELLOW}建议：为提高拉取速度，可配置国内镜像加速：${NC}"
    echo -e "1. 编辑 /etc/docker/daemon.json"
    echo -e "2. 添加示例配置："
    echo -e '   { "registry-mirrors": ["https://registry.mirror.aliyuncs.com"] }'
    echo -e "3. 执行：sudo systemctl restart docker"
  else
    echo -e "${GREEN}检测到已配置镜像加速${NC}"
  fi
}

check_existing_container() {
    local container_name=$(get_container_name)
    local opposite_name=$(get_opposite_container_name)

    # 检查当前容器是否存在
    if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
        echo -e "${GREEN}检测到已存在的容器: ${container_name}${NC}"
        return 0
    # 检查对立容器是否存在（如从 alist-tvbox 切换到 xiaoya-tvbox）
    elif docker ps -a --format '{{.Names}}' | grep -q "^${opposite_name}\$"; then
        echo -e "${YELLOW}检测到对立容器: ${opposite_name}${NC}"
        return 1
    else
        echo -e "${YELLOW}未找到现有容器${NC}"
        return 2
    fi
}

# 规整端口：去除空白/换行，仅保留纯数字；非法则回退默认值。
# 防止从容器反推出的 HostPort 带异常字符（如换行）导致 docker run -p 解析失败。
sanitize_port() {
  local v="${1//[[:space:]]/}"
  local default="$2"
  [[ "$v" =~ ^[0-9]+$ ]] || v="$default"
  printf '%s' "$v"
}

get_container_config() {
    local container_name="${1:-$(get_container_name)}"

    # 获取镜像名称
    CONFIG["IMAGE_NAME"]=$(docker inspect --format '{{.Config.Image}}' "$container_name" 2>/dev/null)
    local image_id
    image_id=$(get_image_id_from_name "${CONFIG[IMAGE_NAME]}" || true)
    [[ -n "$image_id" ]] && CONFIG["IMAGE_ID"]="$image_id"

    # 获取网络模式
    CONFIG["NETWORK"]=$(docker inspect --format '{{.HostConfig.NetworkMode}}' "$container_name" 2>/dev/null)

    # 获取端口映射（非 host 网络时）
    if [[ "${CONFIG[NETWORK]}" != "host" ]]; then
        local p1 p2 alist_port_key
        p1="$(docker inspect --format '{{(index (index .NetworkSettings.Ports "4567/tcp") 0).HostPort}}' "$container_name" 2>/dev/null || true)"
        alist_port_key="$(get_alist_container_port "${CONFIG[IMAGE_NAME]}")"
        p2="$(docker inspect --format "{{(index (index .NetworkSettings.Ports \"$alist_port_key\") 0).HostPort}}" "$container_name" 2>/dev/null || true)"
        CONFIG["PORT1"]="$(sanitize_port "$p1" 4567)"
        CONFIG["PORT2"]="$(sanitize_port "$p2" 5344)"
    fi

    # 获取重启策略
    CONFIG["RESTART"]=$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' "$container_name" 2>/dev/null || echo "always")

    # 获取数据目录（从挂载点反推）
    local mount_path=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}' "$container_name" 2>/dev/null)
    [[ -n "$mount_path" ]] && CONFIG["BASE_DIR"]="$mount_path"

    echo -e "${CYAN}已从现有容器加载配置${NC}"
}

# 从现存容器同步运行时配置（镜像/网络/端口/重启策略）到内存 CONFIG，
# 用于显示真实状态——即使用户绕过脚本手动改动了容器（如切换版本、host<->bridge）。
# 故意不覆盖 BASE_DIR（以免影响 install/update 的迁移逻辑），也不写回 app.conf。
sync_runtime_config() {
  local name="" n
  for n in "$(get_container_name)" "$(get_opposite_container_name)"; do
    [[ -n "$n" ]] || continue
    if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q "^${n}\$"; then
      name="$n"
      break
    fi
  done
  [[ -n "$name" ]] || return 1

  local saved_base="${CONFIG[BASE_DIR]:-}"
  get_container_config "$name" >/dev/null
  [[ -n "$saved_base" ]] && CONFIG["BASE_DIR"]="$saved_base"
  return 0
}

get_image_id_from_name() {
  local image="$1"
  local key candidate
  for key in "${!VERSIONS[@]}"; do
    candidate="${VERSIONS[$key]%% - *}"
    candidate="${candidate//[[:space:]]/}"
    if [[ "$candidate" == "$image" || "${candidate}:latest" == "$image" || "$candidate" == "${image%:latest}" ]]; then
      echo "$key"
      return 0
    fi
  done
  return 1
}

get_alist_container_port() {
  local image="$1"
  case "$image" in
    *alist-tvbox*) echo "5244/tcp" ;;
    *) echo "80/tcp" ;;
  esac
}

# 加载配置
load_config() {
  if [[ -f "$CONFIG_FILE" ]]; then
    while IFS='=' read -r key value; do
      if [[ -n "$key" ]]; then
        CONFIG["$key"]="$value"
      fi
    done < "$CONFIG_FILE"
  else
    for key in "${!DEFAULT_CONFIG[@]}"; do
      CONFIG["$key"]="${DEFAULT_CONFIG[$key]}"
    done

    if check_nas_environment; then
        # 如果是 NAS，调整配置（如改用 host 网络、优化存储路径等）
        CONFIG["BASE_DIR"]="/volume1/docker/alist-tvbox"  # 群晖常用 Docker 数据目录
        CONFIG["NETWORK"]="host"  # NAS 上推荐 host 网络模式
        CONFIG["IMAGE_ID"]="7"
        CONFIG["IMAGE_NAME"]="haroldli/xiaoya-tvbox:host"
    fi

    echo -e "${CYAN}首次运行，正在检测现有容器...${NC}"
    if check_existing_container; then
       get_container_config "$(get_container_name)"
    else
       local existing_status=$?
       if [[ "$existing_status" -eq 1 ]]; then
         get_container_config "$(get_opposite_container_name)"
       fi
    fi

    mkdir -p "$(dirname "$CONFIG_FILE")"
    save_config

    # 确保基础目录存在
    if [[ ! -d "${CONFIG[BASE_DIR]}" ]]; then
      mkdir -p "${CONFIG[BASE_DIR]}"
      echo -e "${YELLOW}创建基础目录: ${CONFIG[BASE_DIR]}${NC}"
    fi
    read -n 1 -s -r -p "按任意键继续..."
  fi
}

# 保存配置
save_config() {
  {
    for key in "${!CONFIG[@]}"; do
      echo "$key=${CONFIG[$key]}"
    done
  } > "$CONFIG_FILE"
  chmod 600 "$CONFIG_FILE"
}

# Get container name
get_container_name() {
  case "${CONFIG[IMAGE_NAME]}" in
    *alist-tvbox*) echo "alist-tvbox" ;;
    *) echo "xiaoya-tvbox" ;;
  esac
}

# Get opposite container name
get_opposite_container_name() {
  case "${CONFIG[IMAGE_NAME]}" in
    *alist-tvbox*) echo "xiaoya-tvbox" ;;
    *) echo "alist-tvbox" ;;
  esac
}

# 停止并移除对立容器
remove_opposite_container() {
  local opposite_name=$(get_opposite_container_name)

  if docker ps -a --format '{{.Names}}' | grep -q "^${opposite_name}\$"; then
    echo -e "${YELLOW}正在移除容器 ${opposite_name}...${NC}"
    docker rm -f "$opposite_name" >/dev/null
  fi
}

# 检测容器状态
check_container_status() {
  local container_name=$(get_container_name)
  if docker ps --format '{{.Names}}' | grep -q "^${container_name}\$"; then
    echo "running"
  elif docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
    echo "stopped"
  else
    echo "not_exist"
  fi
}

# 检查镜像更新
check_image_update() {
  local image="${CONFIG[IMAGE_NAME]}"
  local platform=""

  # 检测当前平台
  if [[ $(uname -m) == "aarch64" || $(uname -m) == "arm64" ]]; then
    platform="--platform=linux/arm64"
    echo -e "${CYAN}检测到 ARM64 平台，强制使用 arm64 镜像${NC}"
  fi

  local current_id=$(docker images --quiet "$image")
  echo -e "${CYAN}正在拉取镜像：${image}${NC}"
  if ! docker pull $platform "$image" >/dev/null; then
    echo -e "${RED}镜像拉取失败! 可能原因：${NC}"
    echo -e "1. 镜像未提供 ARM64 版本"
    echo -e "2. 镜像名称错误"
    return 1
  fi
  local new_id=$(docker images --quiet "$image")

  if [[ "$current_id" != "$new_id" ]]; then
    echo -e "${GREEN}检测到新版本镜像${NC}"
    return 0
  else
    echo -e "${YELLOW}当前已是最新版本${NC}"
    return 1
  fi
}

# 把旧的命名卷 tvbox-www-static 中的静态文件一次性迁移到绑定目录
# ${CONFIG[BASE_DIR]}/www-static。仅在命名卷存在、目标为空时复制，幂等。
migrate_www_static() {
  local volume="tvbox-www-static"
  local target="${CONFIG[BASE_DIR]}/www-static"
  local marker="${CONFIG[BASE_DIR]}/.www-static-migrated"

  mkdir -p "$target"

  # 已迁移过则跳过
  [[ -f "$marker" ]] && return 0

  # 旧命名卷不存在，无需迁移
  if ! docker volume inspect "$volume" >/dev/null 2>&1; then
    touch "$marker"
    return 0
  fi

  # 目标已有数据则不覆盖，仅标记完成
  if [[ -n "$(ls -A "$target" 2>/dev/null)" ]]; then
    touch "$marker"
    return 0
  fi

  echo -e "${YELLOW}正在迁移静态文件: 命名卷 $volume -> $target${NC}"
  if docker run --rm \
      -v "${volume}:/from:ro" \
      -v "${target}:/to" \
      --entrypoint sh \
      "${CONFIG[IMAGE_NAME]}" -c "cp -a /from/. /to/" 2>/dev/null; then
    touch "$marker"
    echo -e "${GREEN}静态文件迁移完成${NC}"
  else
    echo -e "${RED}静态文件迁移失败：请手动从卷 $volume 复制到 $target${NC}"
    return 1
  fi
}

# 启动容器
start_container() {
  local image="${CONFIG[IMAGE_NAME]}"
  local container_name=$(get_container_name)
  local aList_port=80

  # 确保数据目录存在
  mkdir -p "${CONFIG[BASE_DIR]}"
  migrate_www_static || true

  if [[ -n "${CONFIG[GITHUB_PROXY]}" ]]; then
    # 支持多个代理，用逗号分隔，写入时每行一个
    echo "${CONFIG[GITHUB_PROXY]}" | tr ',' '\n' | sed '/^[[:space:]]*$/d' > "${CONFIG[BASE_DIR]}/github_proxy.txt"
  else
    rm -f "${CONFIG[BASE_DIR]}/github_proxy.txt"
  fi

  # 统一构造 docker run 参数：用条件追加而非展开可能为空的数组，
  # 避免在 set -u 下（NAS 常见的 bash 4.3 及更早版本）报 "unbound variable"
  local -a run_args=(
    -d
    --name "$container_name"
    -e ALIST_PORT="${CONFIG[PORT2]}"
    -e MEM_OPT="-Xmx512M"
    -v "${CONFIG[BASE_DIR]}":/data
    -v "${CONFIG[BASE_DIR]}/www-static":/www/static
    --restart="${CONFIG[RESTART]}"
  )

  if [[ "${CONFIG[IMAGE_NAME]}" == *"alist-tvbox"* ]]; then
    aList_port=5244
    run_args+=("-v" "${CONFIG[BASE_DIR]}/alist:/opt/alist/data")
  fi

  # 添加/www挂载选项
  if [[ "${CONFIG[MOUNT_WWW]}" == "true" ]]; then
    mkdir -p "${CONFIG[BASE_DIR]}/www"
    run_args+=("-v" "${CONFIG[BASE_DIR]}/www:/www")
  fi

  # 添加自定义挂载
  if [[ -f "${CONFIG[BASE_DIR]}/mounts.conf" ]]; then
    while IFS= read -r line; do
      [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
      # 检查主机目录是否存在，不存在则创建
      local host_dir="${line%%:*}"
      if [[ ! -e "$host_dir" ]]; then
        mkdir -p "$host_dir"
        echo -e "${YELLOW}已创建主机目录: $host_dir${NC}"
      fi
      run_args+=("-v" "$line")
    done < "${CONFIG[BASE_DIR]}/mounts.conf"
  fi

  # host 模式直接使用主机网络不映射端口；否则按端口映射
  if [[ "${CONFIG[NETWORK]}" == "host" ]]; then
    run_args+=("--network" "host")
    echo -e "${YELLOW}使用host网络模式${NC}"
  else
    run_args+=("-p" "${CONFIG[PORT1]}:4567" "-p" "${CONFIG[PORT2]}:${aList_port}")
  fi

  docker run "${run_args[@]}" "$image"
}

# 显示访问信息
show_access_info() {
  sync_runtime_config || true
  local container_name=$(get_container_name)
  local ip=$(get_host_ip)

  echo -e "\n${CYAN}============== 访问信息 ==============${NC}"
  echo -e "容器名称: ${GREEN}${container_name}${NC}"
  if [[ "${CONFIG[NETWORK]}" == "host" ]]; then
    echo -e "管理界面: ${GREEN}http://${ip:-localhost}:4567/${NC}"
    echo -e "Nginx界面: ${GREEN}http://${ip:-localhost}:5678/${NC}"
    echo -e "httpd服务: ${GREEN}http://${ip:-localhost}:5233/${NC}"
    echo -e "AList界面: ${GREEN}http://${ip:-localhost}:5234/${NC}"
  else
    echo -e "管理界面: ${GREEN}http://${ip:-localhost}:${CONFIG[PORT1]}/${NC}"
    echo -e "AList界面: ${GREEN}http://${ip:-localhost}:${CONFIG[PORT2]}/${NC}"
  fi
  echo -e "${CYAN}=======================================${NC}"
  if [[  "$#" -ge 1 && "$1" == "true" ]]; then
    local credentials="${CONFIG[BASE_DIR]}/initial_admin_credentials.txt"
    echo -e "${GREEN}帐号密码请查看文件：$credentials${NC}"
  fi
  echo -e "查看日志: ${YELLOW}docker logs -f $container_name${NC}"
}

# 显示交互式菜单
show_menu() {
  clear
  sync_runtime_config || true
  local status=$(check_container_status)
  local container_name=$(get_container_name)
  local sys=$(uname -mor)
  local docker_version=$(docker version --format '{{.Server.Version}}')

  echo -e "${CYAN}==============================================${NC}"
  echo -e "${GREEN}          AList TvBox 安装升级配置管理          ${NC}"
  echo -e "${CYAN}==============================================${NC}"
  echo -e "${YELLOW} 镜像版本: ${CONFIG[IMAGE_NAME]}${NC}"
  echo -e "${YELLOW} 容器名称: ${container_name}${NC}"
  echo -e "${YELLOW} 容器状态: $(
    case "$status" in
      "running") echo -e "${GREEN}运行中${NC}";;
      "stopped") echo -e "${RED}已停止${NC}";;
      *) echo -e "${YELLOW}未创建${NC}";;
    esac
  )${NC}"
  echo -e "${YELLOW} 网络模式: ${CONFIG[NETWORK]}${NC}"
  echo -e "${YELLOW} 重启策略: ${CONFIG[RESTART]}${NC}"
  echo -e "${YELLOW} 系统信息: ${sys}${NC}"
  echo -e "${YELLOW} Docker: ${docker_version}${NC}"
  echo -e "${YELLOW} 脚本版本: ${SCRIPT_VERSION}${NC}"
  echo -e "${CYAN}---------------------------------------------${NC}"
  echo -e "${GREEN} 1. 安装/更新${NC}"

  # 动态菜单项
  case "$status" in
    "running")
      echo -e "${GREEN} 2. 停止容器${NC}"
      ;;
    *)
      echo -e "${GREEN} 2. 启动容器${NC}"
      ;;
  esac

  echo -e "${GREEN} 3. 重启容器${NC}"
  echo -e "${GREEN} 4. 查看状态${NC}"
  echo -e "${GREEN} 5. 日志管理${NC}"
  echo -e "${GREEN} 6. 卸载容器${NC}"
  echo -e "${GREEN} 7. 选择版本${NC}"
  echo -e "${GREEN} 8. 配置管理${NC}"
  echo -e "${GREEN} 9. 自动修复${NC}"
  echo -e "${GREEN} c. 清理资源${NC}"
  echo -e "${GREEN} w. 打开Web界面${NC}"
  echo -e "${GREEN} 0. 退出${NC}"
  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "请输入选项 [0-9/c/w]: " choice
}

# 检查系统架构支持
check_architecture_support() {
  local arch=$(uname -m)

  case "$arch" in
    x86_64)  return 0 ;;  # 支持 amd64
    aarch64)
      # ARM64 平台，检查是否选择了不支持的版本
      if [[ "${CONFIG[IMAGE_ID]}" == "2" || "${CONFIG[IMAGE_ID]}" == "5" || "${CONFIG[IMAGE_ID]}" == "6" ]]; then
        echo -e "${RED}错误: ARM64 不支持native版本${NC}"
        echo -e "请选择其他版本（如 1、3、4、7、8）"
        return 1
      fi
      return 0 ;;  # 支持 arm64
    armv*)
      echo -e "${RED}错误: 不支持 ARMv7 (32位) 架构${NC}"
      return 1
      ;;
    *)
      echo -e "${RED}错误: 不支持的架构: $arch${NC}"
      return 1
      ;;
  esac
}

# 验证镜像与网络模式的兼容性
validate_image_network_compatibility() {
  local image="${CONFIG[IMAGE_NAME]}"
  local network="${CONFIG[NETWORK]}"

  # host镜像必须使用host网络（匹配 :host 或 -host 后缀）
  if [[ "$image" =~ (:|-)host$ && "$network" != "host" ]]; then
    echo -e "${RED}错误: ${image} 必须使用 host 网络模式${NC}"
    echo -e "${YELLOW}该镜像专为 host 网络优化，不支持端口映射${NC}"
    return 1
  fi

  # host网络必须使用host镜像
  if [[ "$network" == "host" && ! "$image" =~ (:|-)host ]]; then
    echo -e "${YELLOW}host 网络模式建议使用 host 镜像版本${NC}"
    echo -e "${YELLOW}普通镜像的 AList 监听 80 端口，会占用主机端口${NC}"
    echo -e "${YELLOW}请选择版本 6 (native-host) 或版本 7 (host)${NC}"
  fi

  return 0
}

# 安装/更新容器

# -------------------------
# LEGACY MIGRATION (v3)
# -------------------------
migrate_legacy_data() {
  local legacy_dir="/etc/xiaoya"
  local new_dir="${CONFIG[BASE_DIR]:-/opt/alist-tvbox}"

  # 如果新旧路径相同，无需迁移
  if [[ "$new_dir" == "$legacy_dir" ]]; then
    return 0
  fi

  mkdir -p "$new_dir"

  # 检查是否已经迁移完成
  if [[ -f "$new_dir/.v3" ]]; then
    echo -e "${GREEN}数据已迁移（存在标记文件 .v3），跳过${NC}"
    return 0
  fi

  # 从现存容器的 /data 挂载反推用户实际使用的数据目录
  local bound_source=""
  local container_found=""
  local name
  for name in "$(get_container_name)" "$(get_opposite_container_name)"; do
    [[ -n "$name" ]] || continue
    if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q "^${name}\$"; then
      bound_source="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}' "$name" 2>/dev/null)"
      if [[ -n "$bound_source" ]]; then
        container_found="$name"
        break
      fi
    fi
  done

  # 场景1：容器正在使用旧路径 /etc/xiaoya，需要迁移
  if [[ "$bound_source" == "$legacy_dir" && -d "$legacy_dir" ]]; then
    echo -e "${CYAN}检测到容器 $container_found 正在使用旧路径: $legacy_dir${NC}"
    echo -e "${YELLOW}正在迁移数据: $legacy_dir -> $new_dir${NC}"

    # 如果目标目录非空，警告用户
    if [[ -n "$(ls -A "$new_dir" 2>/dev/null)" ]]; then
      echo -e "${RED}警告：目标目录 $new_dir 已存在数据！${NC}"
      echo -e "${YELLOW}建议：请手动检查并备份目标目录，或删除后重试${NC}"
      echo -e "${YELLOW}跳过自动迁移，将使用目标目录现有数据${NC}"
      touch "$new_dir/.v3"
      return 0
    fi

    if cp -a "$legacy_dir/." "$new_dir/"; then
      touch "$new_dir/.v3"
      echo -e "${GREEN}✓ 迁移完成: $legacy_dir -> $new_dir${NC}"
      echo -e "${CYAN}提示：旧目录 $legacy_dir 仍然保留，确认无误后可手动删除${NC}"
    else
      # 清理半成品
      if [[ -n "$new_dir" && "$new_dir" != "/" ]]; then
        find "$new_dir" -mindepth 1 -delete 2>/dev/null || true
      fi
      echo -e "${RED}✗ 迁移失败：已清理目标目录${NC}"
      echo -e "${YELLOW}请检查权限后重试，或手动复制: cp -a $legacy_dir/. $new_dir/${NC}"
      return 1
    fi
    return 0
  fi

  # 场景2：容器不存在或使用其他路径，但旧路径有数据，新路径为空
  if [[ -d "$legacy_dir" && -n "$(ls -A "$legacy_dir" 2>/dev/null)" ]]; then
    # 新目录为空，可以尝试迁移
    if [[ -z "$(ls -A "$new_dir" 2>/dev/null)" ]]; then
      echo -e "${YELLOW}检测到旧路径 $legacy_dir 存在数据，但容器未使用该路径${NC}"
      read -p "是否从旧路径迁移数据？[Y/n] " yn
      case "$yn" in
        [Nn]*)
          echo -e "${YELLOW}跳过迁移${NC}"
          touch "$new_dir/.v3"
          return 0
          ;;
        *)
          echo -e "${CYAN}正在迁移数据: $legacy_dir -> $new_dir${NC}"
          if cp -a "$legacy_dir/." "$new_dir/"; then
            touch "$new_dir/.v3"
            echo -e "${GREEN}✓ 迁移完成${NC}"
            echo -e "${CYAN}提示：旧目录 $legacy_dir 仍然保留，确认无误后可手动删除${NC}"
          else
            find "$new_dir" -mindepth 1 -delete 2>/dev/null || true
            echo -e "${RED}✗ 迁移失败${NC}"
            return 1
          fi
          return 0
          ;;
      esac
    else
      # 新目录已有数据，直接标记完成
      echo -e "${YELLOW}目标目录 $new_dir 已有数据，跳过迁移${NC}"
      touch "$new_dir/.v3"
      return 0
    fi
  fi

  # 场景3：旧路径不存在或为空，无需迁移
  touch "$new_dir/.v3"
  echo -e "${GREEN}无需从旧路径迁移（旧路径不存在或为空）${NC}"
  return 0
}


install_container() {
  # 先检查架构支持
  if ! check_architecture_support; then
    return 1
  fi

  # 如果镜像名称包含host后缀，自动切换网络模式
  if [[ "${CONFIG[IMAGE_NAME]}" =~ (:|-)host$ ]]; then
    CONFIG["NETWORK"]="host"
    echo -e "${YELLOW}检测到host版本，已自动切换网络模式为host${NC}"
    save_config
  fi

  # 验证镜像与网络模式的兼容性
  if ! validate_image_network_compatibility; then
    read -n 1 -s -r -p "按任意键继续..."
    return 1
  fi

  local container_name=$(get_container_name)

  # 在删除容器之前尝试迁移数据
  # 此时容器还在，可以检测到它使用的旧路径
  if ! migrate_legacy_data; then
    echo -e "${RED}数据迁移失败，安装中止${NC}"
    read -n 1 -s -r -p "按任意键继续..."
    return 1
  fi

  remove_opposite_container

  INIT=false
  # 检查基础目录是否存在
  if [[ ! -d "${CONFIG[BASE_DIR]}" ]]; then
    echo -e "${YELLOW}基础目录不存在，正在创建: ${CONFIG[BASE_DIR]}${NC}"
    mkdir -p "${CONFIG[BASE_DIR]}"
    INIT=true
  fi

  # 检查基础目录是否为空（排除 .v3 标记文件）
  local file_count=$(find "${CONFIG[BASE_DIR]}" -mindepth 1 ! -name '.v3' 2>/dev/null | wc -l)
  if [[ "$file_count" -eq 0 ]]; then
    INIT=true
  fi

  if check_image_update; then
    echo -e "${GREEN}正在更新容器...${NC}"
  else
    echo -e "${YELLOW}没有新版本可用，继续使用当前镜像${NC}"
  fi

  if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
    echo -e "${YELLOW}正在移除现有容器...${NC}"
    docker rm -f "$container_name" >/dev/null
  fi

  start_container
  echo -e "${GREEN}操作成功完成!${NC}"
  show_access_info $INIT
  read -n 1 -s -r -p "按任意键继续..."
}

# 检查更新
check_update() {
  local auto_update=false
  # 检查是否包含-y参数
  if [[ "$#" -ge 1 && "$1" == "-y" ]]; then
    auto_update=true
  fi

  local image="${CONFIG[IMAGE_NAME]}"
  local platform=""

  if [[ $(uname -m) == "aarch64" || $(uname -m) == "arm64" ]]; then
    platform="--platform=linux/arm64"
    echo -e "${CYAN}检测到 ARM64 平台，强制使用 arm64 镜像${NC}"
  fi

  echo -e "${CYAN}正在检查镜像更新...${NC}"

  local current_id=$(docker images --quiet "$image")
  echo -e "${CYAN}正在拉取镜像: ${CONFIG[IMAGE_NAME]}${NC}"
  if ! docker pull $platform "${CONFIG[IMAGE_NAME]}" >/dev/null; then
    echo -e "${RED}镜像拉取失败!${NC}"
    return 1
  fi
  local new_id=$(docker images --quiet "$image")

  if [[ "$current_id" != "$new_id" ]]; then
    echo -e "${GREEN}检测到新版本镜像${NC}"
    if [[ "$auto_update" == true ]]; then
      replace_container
      return 0
    else
      read -p "检测到新版本，是否立即更新容器？[Y/n] " yn
      case $yn in
        [Nn]* ) ;;
        * )
          replace_container
          ;;
      esac
    fi
  else
    echo -e "${YELLOW}当前已是最新版本${NC}"
    return 1
  fi
}

replace_container() {
  local container_name
  container_name=$(get_container_name)
  if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
    echo -e "${YELLOW}正在重建容器...${NC}"
    docker rm -f "$container_name" >/dev/null
  else
    echo -e "${GREEN}正在启动容器...${NC}"
  fi
  start_container
}

# 显示版本选择菜单
show_version_menu() {
  while true; do
    clear
    echo -e "${CYAN}=============================================${NC}"
    echo -e "${GREEN}          请选择要使用的版本          ${NC}"
    echo -e "${CYAN}=============================================${NC}"

    local arch=$(uname -m)
    local current_version="${CONFIG[IMAGE_ID]}"

    for key in {1..9}; do
      # 如果是 ARM64 并且是版本 2、5、6，则跳过
      if [[ "$arch" == "aarch64" && ("$key" == "2" || "$key" == "5" || "$key" == "6") ]]; then
        continue
      fi
      if [[ "$key" == "$current_version" ]]; then
        echo -e "${GREEN} $key. ${VERSIONS[$key]}${NC} (当前使用)"
      else
        echo -e "${YELLOW} $key. ${VERSIONS[$key]}${NC}"
      fi
    done

    echo -e "${GREEN} 0. 返回主菜单${NC}"
    echo -e "${CYAN}---------------------------------------------${NC}"

    while true; do
      read -p "请输入版本编号 [0-9]: " version_choice
      # 如果是 ARM64，不允许选择 2、5、6
      if [[ "$arch" == "aarch64" && ("$version_choice" == "2" || "$version_choice" == "5" || "$version_choice" == "6") ]]; then
        echo -e "${RED}ARM64 不支持该版本，请选择其他选项${NC}"
        continue
      fi
      # 验证输入是否为0-9的数字
      if [[ "$version_choice" =~ ^[0-9]$ ]]; then
        break
      else
        echo -e "${RED}无效输入! 请输入0-9的数字${NC}"
      fi
    done

    # 如果选择0，返回主菜单
    if [[ "$version_choice" == "0" ]]; then
      return
    fi

    local old_version="${CONFIG[IMAGE_NAME]}"
    local old_image_id="${CONFIG[IMAGE_ID]}"
    local image="${VERSIONS[$version_choice]}"
    image=$(echo "$image" | awk -F' - ' '{print $1}' | tr -d ' ')
    CONFIG["IMAGE_ID"]="$version_choice"
    CONFIG["IMAGE_NAME"]="${image}"

    # 新增：如果镜像名称包含host后缀，自动切换网络模式
    if [[ "${image}" =~ (:|-)host$ ]]; then
      CONFIG["NETWORK"]="host"
      echo -e "${YELLOW}检测到host版本，已自动切换网络模式为host${NC}"
    fi

    save_config

    # 验证镜像与网络模式的兼容性
    if ! validate_image_network_compatibility; then
      echo -e "${RED}镜像与网络模式不兼容，版本切换失败${NC}"
      CONFIG["IMAGE_ID"]="$old_image_id"
      CONFIG["IMAGE_NAME"]="$old_version"
      save_config
      read -n 1 -s -r -p "按任意键继续..."
      return
    fi

    # 获取容器名称
    local container_name=$(get_container_name)
    local opposite_name=$(get_opposite_container_name)

    # 删除对立容器
    if docker ps -a --format '{{.Names}}' | grep -q "^${opposite_name}\$"; then
      echo -e "${YELLOW}正在移除对立容器 ${opposite_name}...${NC}"
      docker rm -f "$opposite_name" >/dev/null
    fi

    # 如果容器存在，则停止并删除
    if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
      echo -e "${YELLOW}正在停止并删除旧容器...${NC}"
      docker rm -f "$container_name" >/dev/null
    fi

    # 拉取最新镜像
    echo -e "${YELLOW}正在拉取最新镜像...${NC}"
    if ! docker pull "${CONFIG[IMAGE_NAME]}"; then
      echo -e "${RED}拉取镜像失败，版本切换终止${NC}"
      CONFIG["IMAGE_ID"]="$old_image_id"
      CONFIG["IMAGE_NAME"]="$old_version"
      save_config
      read -n 1 -s -r -p "按任意键继续..."
      return
    fi

    # 启动新容器
    echo -e "${YELLOW}正在启动新版本容器...${NC}"
    start_container

    echo -e "${GREEN}版本已切换为: ${image}${NC}"
    show_access_info false
    read -n 1 -s -r -p "按任意键继续..."
    return
  done
}

reset_admin_password() {
  local container_name=$(get_container_name)
  local status=$(check_container_status)

  if [[ "$status" == "running" ]]; then
    local reset_token
    reset_token="$(generate_admin_reset_token)"
    echo -e "${YELLOW}正在通过容器内接口重置管理员密码...${NC}"
    if ! write_admin_reset_token "$container_name" "$reset_token"; then
      echo -e "${RED}密码重置失败：无法写入容器内重置令牌${NC}"
      echo -e "${YELLOW}请查看日志：docker logs -f $container_name${NC}"
      read -n 1 -s -r -p "按任意键继续..."
      return
    fi

    local response
    if response="$(call_admin_password_reset_api "$container_name" "$reset_token" 2>/dev/null)"; then
      local password
      password="$(parse_reset_password_response "$response")"
      if [[ -n "$password" ]]; then
        echo -e "${GREEN}管理员账号已重置为：admin${NC}"
        echo -e "${GREEN}管理员密码已重置为：$password${NC}"
        echo -e "${YELLOW}请尽快登录管理界面修改密码!${NC}"
      else
        echo -e "${RED}密码重置失败：接口返回内容无法解析${NC}"
        echo -e "${YELLOW}返回内容：$response${NC}"
      fi
    else
      echo -e "${RED}密码重置失败：无法调用容器内接口${NC}"
      echo -e "${YELLOW}请查看日志：docker logs -f $container_name${NC}"
    fi
  else
    echo -e "${RED}容器未运行，无法通过本地接口重置管理员密码${NC}"
  fi

  read -n 1 -s -r -p "按任意键继续..."
}

parse_reset_password_response() {
  local response="$1"
  printf '%s' "$response" | tr -d '\n' | sed -n 's/.*"password"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

generate_admin_reset_token() {
  local token=""
  while [[ ${#token} -lt 32 ]]; do
    token="${token}$(dd if=/dev/urandom bs=24 count=1 2>/dev/null | base64 | tr -dc 'A-Za-z0-9')"
  done
  printf '%s\n' "${token:0:32}"
}

write_admin_reset_token() {
  local container_name="$1"
  local reset_token="$2"

  docker exec "$container_name" sh -lc "mkdir -p /data/atv && umask 077 && printf '%s' '$reset_token' > /data/atv/admin_reset_token"
}

call_admin_password_reset_api() {
  local container_name="$1"
  local reset_token="$2"

  docker exec "$container_name" sh -lc "curl -fsS -X POST -H 'X-ADMIN-RESET-TOKEN: $reset_token' http://127.0.0.1:4567/api/local/admin/password"
}

# 管理自定义挂载目录
manage_custom_mounts() {
  while true; do
    clear
    echo -e "${CYAN}=============================================${NC}"
    echo -e "${GREEN}          自定义挂载目录管理          ${NC}"
    echo -e "${CYAN}=============================================${NC}"

    # 显示当前挂载
    if [[ -f "${CONFIG[BASE_DIR]}/mounts.conf" ]]; then
      echo -e "${YELLOW}当前挂载配置:${NC}"
      if cat "${CONFIG[BASE_DIR]}/mounts.conf" 2>/dev/null | awk '{print " " NR ". " $0}'; then
        :
      else
        echo -e "${RED}无法读取挂载配置文件 (权限不足)${NC}"
      fi
    else
      echo -e "${YELLOW}暂无自定义挂载${NC}"
    fi

    echo -e "\n${GREEN} 1. 添加挂载目录"
    echo -e " 2. 删除挂载目录"
    echo -e " 0. 返回配置菜单${NC}"
    echo -e "${CYAN}---------------------------------------------${NC}"
    read -p "请选择操作 [0-2]: " mount_choice

    case $mount_choice in
      1)
        add_custom_mount
        ;;
      2)
        remove_custom_mount
        ;;
      0)
        break
        ;;
      *)
        echo -e "${RED}无效选择!${NC}"
        sleep 1
        ;;
    esac
  done
}

# 添加自定义挂载
add_custom_mount() {
  echo -e "${YELLOW}格式: 主机目录:容器目录[:权限]"
  echo -e "示例: /path/on/host:/path/in/container:ro${NC}"
  read -p "请输入挂载配置: " mount_config

  # 基本格式验证
  if [[ "$mount_config" =~ ^[^:]+:[^:]+(:ro|:rw)?$ ]]; then
    mkdir -p "${CONFIG[BASE_DIR]}" 2>/dev/null || {
      echo -e "${RED}无法创建数据目录 (权限不足)${NC}"
      sleep 1
      return
    }
    if echo "$mount_config" >> "${CONFIG[BASE_DIR]}/mounts.conf" 2>/dev/null; then
      echo -e "${GREEN}挂载配置已添加!${NC}"
      # 自动重建容器使挂载生效
      recreate_container_for_mounts
    else
      echo -e "${RED}添加失败 (权限不足)${NC}"
    fi
  else
    echo -e "${RED}无效格式! 请使用 主机目录:容器目录[:权限] 格式${NC}"
  fi
  sleep 1
}

# 删除自定义挂载
remove_custom_mount() {
  if [[ ! -f "${CONFIG[BASE_DIR]}/mounts.conf" ]]; then
    echo -e "${YELLOW}暂无自定义挂载配置${NC}"
    sleep 1
    return
  fi

  read -p "请输入要删除的挂载编号: " mount_num
  local total_lines
  total_lines=$(wc -l < "${CONFIG[BASE_DIR]}/mounts.conf" 2>/dev/null) || {
    echo -e "${RED}无法读取挂载配置文件 (权限不足)${NC}"
    sleep 1
    return
  }

  if [[ "$mount_num" =~ ^[0-9]+$ ]] && [[ "$mount_num" -ge 1 ]] && [[ "$mount_num" -le "$total_lines" ]]; then
    # 创建临时文件
    local temp_file=$(mktemp)
    # 删除指定行
    if sed "${mount_num}d" "${CONFIG[BASE_DIR]}/mounts.conf" > "$temp_file" 2>/dev/null && \
       mv "$temp_file" "${CONFIG[BASE_DIR]}/mounts.conf" 2>/dev/null; then
      echo -e "${GREEN}挂载配置已删除!${NC}"
      # 自动重建容器使挂载生效
      recreate_container_for_mounts
    else
      echo -e "${RED}删除失败 (权限不足)${NC}"
      rm -f "$temp_file" 2>/dev/null
    fi
  else
    echo -e "${RED}无效编号!${NC}"
  fi
  sleep 1
}

# 重建容器使挂载生效
recreate_container_for_mounts() {
  recreate_existing_container "挂载配置" "容器不存在，挂载配置将在下次启动时生效"
}

# 重建容器使配置变更生效
recreate_container_for_changes() {
  recreate_existing_container "配置变更" "容器不存在，变更将在下次启动时生效"
  sleep 1
}

recreate_existing_container() {
  local reason="$1"
  local missing_message="$2"
  local container_name=$(get_container_name)

  if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
    echo -e "${YELLOW}正在重建容器使${reason}生效...${NC}"
    local was_running=$(docker inspect -f '{{.State.Running}}' "$container_name" 2>/dev/null)

    # 停止并删除现有容器
    docker rm -f "$container_name" >/dev/null

    # 重新创建容器；原来是停止状态时，创建后恢复为停止状态。
    start_container
    if [[ "$was_running" == "true" ]]; then
      echo -e "${GREEN}容器已重建并启动!${NC}"
    else
      docker stop "$container_name" >/dev/null
      echo -e "${GREEN}容器已重建!${NC}"
    fi
  else
    echo -e "${YELLOW}${missing_message}${NC}"
  fi
}

# 获取当前主机IP
get_host_ip() {
  local ip
  if command -v ip &>/dev/null; then
    ip=$(ip route get 1.1.1.1 2>/dev/null | awk '/src/ {for (i=1;i<=NF;i++) if ($i=="src") print $(i+1); exit}')
  elif command -v hostname &>/dev/null; then
    ip=$(hostname -I | tr ' ' '\n' | grep -v '^127\.' | head -n1)
  else
    ip="localhost"
  fi
  echo "$ip"
}

# 检查 AList 运行状态
check_alist_status() {
  local ip
  ip="$(get_host_ip)"
  local port="${CONFIG[PORT1]}"
  local api_url="http://${ip:-localhost}:$port/api/alist/status"

  echo -e "${CYAN}正在检查 AList 状态...${NC}"

  local response
  if response="$(curl -fsS --connect-timeout 3 "$api_url" 2>/dev/null)"; then
    local status_code
    status_code="$(printf '%s' "$response" | sed -n 's/.*"status"[[:space:]]*:[[:space:]]*\([0-9]\+\).*/\1/p')"
    [[ -z "$status_code" ]] && status_code="$(printf '%s' "$response" | tr -d '\n' | sed -n 's/^\([0-9]\+\)$/\1/p')"

    case "$status_code" in
      0) echo -e "AList 状态: ${RED}未启动${NC}" ;;
      1) echo -e "AList 状态: ${YELLOW}启动中...${NC}" ;;
      2) echo -e "AList 状态: ${GREEN}已启动${NC}" ;;
      *) echo -e "AList 状态: ${RED}未知状态: ${response}${NC}" ;;
    esac
  else
    echo -e "AList 状态: ${RED}无法连接到管理应用${NC}"
  fi
}

check_status() {
  sync_runtime_config || true
  local status=$(check_container_status)
  if [[ "$status" != "running" ]]; then
    echo -e "${YELLOW}容器不存在${NC}"
    sleep 1
    return
  fi
  local container_name=$(get_container_name)

  echo -e "${CYAN}============== 容器基础信息 ==============${NC}"
  docker ps -a --filter "name=$container_name" --format \
    "table {{.Names}}\t{{.Status}}\t{{.Image}}"

  # 从容器实际配置读取网络模式
  local actual_network=$(docker inspect --format '{{.HostConfig.NetworkMode}}' "$container_name" 2>/dev/null)

  # 显示端口映射（支持host和bridge模式）
  echo -e "\n${CYAN}============== 端口映射 ==============${NC}"
  if [[ "$actual_network" == "host" ]]; then
    echo -e "${YELLOW}host模式使用主机网络，无独立端口映射${NC}"

    # 根据镜像类型显示端口
    local image_name=$(docker inspect --format '{{.Config.Image}}' "$container_name" 2>/dev/null)
    if [[ "$image_name" =~ xiaoya.*host ]]; then
      # xiaoya host系列镜像的端口
      echo -e "管理端口: ${GREEN}4567${NC}"
      echo -e "Nginx端口: ${GREEN}5678${NC}"
      echo -e "httpd端口: ${GREEN}5233${NC}"
      echo -e "AList端口: ${GREEN}5234${NC}"
    else
      # 纯净版镜像的端口（host模式直接使用容器端口）
      echo -e "管理端口: ${GREEN}4567${NC}"
      echo -e "AList端口: ${GREEN}5244${NC}"
    fi
  else
    # 使用更安全的方式获取端口映射
    local port_info
    port_info=$(docker port "$container_name" 2>/dev/null)
    if [[ -n "$port_info" ]]; then
      echo "$port_info" | while IFS= read -r line; do
        echo "  $line"
      done
    else
      echo -e "${RED}无端口映射信息${NC}"
    fi
  fi

  # 显示挂载信息（包括自定义挂载）
  echo -e "\n${CYAN}============== 挂载目录 ==============${NC}"
  docker inspect --format \
    '{{range $mount := .Mounts}}{{.Source}} -> {{.Destination}} ({{.Mode}})'$'\n''{{end}}' \
    "$container_name" 2>/dev/null | \
  while IFS= read -r line; do
    [[ -n "$line" ]] && echo "  $line"
  done || true

  echo -e "\n${CYAN}============== 镜像信息 ==============${NC}"
  local image_id=$(docker inspect --format '{{.Image}}' "$container_name" 2>/dev/null | cut -d: -f2 | cut -c1-12)
  local image_name=$(docker inspect --format '{{.Config.Image}}' "$container_name" 2>/dev/null)

  if [[ -n "$image_name" ]]; then
    echo -e "镜像名称: ${GREEN}$image_name${NC}"
    echo -e "镜像ID: ${YELLOW}$image_id${NC}"
    echo -e "创建时间: $(docker inspect --format '{{.Created}}' "$image_name" 2>/dev/null)"
    echo -e "镜像大小: $(docker inspect --format '{{.Size}}' "$image_name" 2>/dev/null | numfmt --to=iec)"
  else
    echo -e "${RED}容器不存在或未使用镜像${NC}"
  fi

  echo -e "\n${CYAN}============= 资源使用情况 ============${NC}"
  docker stats --no-stream "$container_name" 2>/dev/null || echo -e "${YELLOW}容器未运行${NC}"

  # 显示容器内部版本信息
  if [[ "$status" == "running" ]]; then
    echo -e "\n${CYAN}============ 容器版本信息 ============${NC}"

    # 安装模式
    local install_mode=$(docker exec "$container_name" sh -c 'echo $INSTALL' 2>/dev/null || echo "未知")
    echo -e "安装模式: ${GREEN}${install_mode}${NC}"

    # 应用版本
    local app_version=$(docker exec "$container_name" cat /app_version 2>/dev/null | head -1 || echo "未知")
    echo -e "应用版本: ${GREEN}${app_version}${NC}"

    # 小雅版本（如果存在）
    local xiaoya_version=$(docker exec "$container_name" sh -c 'head -n1 /docker.version 2>/dev/null' || echo "")
    if [[ -n "$xiaoya_version" ]]; then
      echo -e "小雅版本: ${GREEN}${xiaoya_version}${NC}"
    fi

    # 容器系统信息
    local container_os=$(docker exec "$container_name" uname -mor 2>/dev/null || echo "未知")
    echo -e "容器系统: ${GREEN}${container_os}${NC}"
  fi

  # 检查AList服务状态
  if [[ "$status" == "running" ]]; then
    echo -e "\n${CYAN}============ AList服务状态 ============${NC}"
    check_alist_status

    # 执行健康检查
    echo ""
    health_check
  fi

  read -n 1 -s -r -p "按任意键继续..."
}

# 立即备份数据库
backup_database_now() {
  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          立即备份数据库          ${NC}"
  echo -e "${CYAN}=============================================${NC}"

  local backup_dir="${CONFIG[BASE_DIR]}/backup"
  local db_file="${CONFIG[BASE_DIR]}/atv.mv.db"

  # 检查数据库文件是否存在
  if [[ ! -f "$db_file" ]]; then
    echo -e "${RED}数据库文件不存在: $db_file${NC}"
    echo -e "${YELLOW}可能原因：容器未运行或数据库未初始化${NC}"
    read -n 1 -s -r -p "按任意键继续..."
    return
  fi

  # 创建备份目录
  mkdir -p "$backup_dir" 2>/dev/null || {
    echo -e "${RED}无法创建备份目录 (权限不足)${NC}"
    read -n 1 -s -r -p "按任意键继续..."
    return
  }

  # 生成备份文件名（格式: database-YYYY-MM-DD-HHMMSS.zip）
  local timestamp=$(date +"%Y-%m-%d-%H%M%S")
  local backup_file="${backup_dir}/database-${timestamp}.zip"

  echo -e "${CYAN}正在备份数据库...${NC}"

  # 使用zip命令打包数据库文件
  if command -v zip >/dev/null 2>&1; then
    if (cd "${CONFIG[BASE_DIR]}" && zip -q "$backup_file" atv.mv.db 2>/dev/null); then
      local filesize=$(ls -lh "$backup_file" | awk '{print $5}')
      echo -e "${GREEN}✓ 备份成功${NC}"
      echo -e "备份文件: ${GREEN}${backup_file}${NC}"
      echo -e "文件大小: ${filesize}"
    else
      echo -e "${RED}备份失败${NC}"
    fi
  else
    echo -e "${RED}错误: 系统未安装 zip 命令${NC}"
    echo -e "${YELLOW}请安装: apt install zip 或 yum install zip${NC}"
  fi

  read -n 1 -s -r -p "按任意键继续..."
}

# GitHub代理管理
manage_github_proxy() {
  while true; do
    clear
    echo -e "${CYAN}=============================================${NC}"
    echo -e "${GREEN}          GitHub代理设置          ${NC}"
    echo -e "${CYAN}=============================================${NC}"

    # 显示当前代理列表
    local proxy_file="${CONFIG[BASE_DIR]}/github_proxy.txt"
    local current_proxies=()

    if [[ -f "$proxy_file" && -s "$proxy_file" ]]; then
      while IFS= read -r line; do
        [[ -n "$line" ]] && current_proxies+=("$line")
      done < "$proxy_file"
    fi

    if [[ ${#current_proxies[@]} -gt 0 ]]; then
      echo -e "${YELLOW}当前代理列表 (最多5个):${NC}\n"
      for i in "${!current_proxies[@]}"; do
        echo -e " $((i+1)). ${GREEN}${current_proxies[$i]}${NC}"
      done
      echo ""
    else
      echo -e "${YELLOW}当前未设置代理${NC}\n"
    fi

    echo -e "${CYAN}支持的代理前缀:${NC}"
    echo -e " - https://ghp.ci/"
    echo -e " - https://github.moeyy.xyz/"
    echo -e " - https://gh-proxy.com/"
    echo -e " 等其他GitHub镜像加速服务\n"

    echo -e "${GREEN} 1. 添加代理"
    echo -e " 2. 删除代理"
    echo -e " 3. 清空所有代理"
    echo -e " 4. 测速对比"
    echo -e " 0. 返回配置菜单${NC}"
    echo -e "${CYAN}---------------------------------------------${NC}"
    read -p "请选择操作 [0-4]: " choice

    case $choice in
      1)
        if [[ ${#current_proxies[@]} -ge 5 ]]; then
          echo -e "${RED}已达到最大代理数量 (5个)${NC}"
          sleep 2
          continue
        fi

        echo -e "${YELLOW}请输入代理URL (必须以 http:// 或 https:// 开头)${NC}"
        echo -e "${YELLOW}示例: https://ghp.ci/${NC}"
        read -p "代理URL: " new_proxy

        # 验证URL格式
        if [[ ! "$new_proxy" =~ ^https?:// ]]; then
          echo -e "${RED}无效的URL格式，必须以 http:// 或 https:// 开头${NC}"
          sleep 2
          continue
        fi

        # 检查是否已存在
        if printf '%s\n' "${current_proxies[@]}" | grep -Fxq "$new_proxy"; then
          echo -e "${YELLOW}该代理已存在${NC}"
          sleep 2
          continue
        fi

        # 添加到列表
        current_proxies+=("$new_proxy")
        save_proxy_list "${current_proxies[@]}"
        echo -e "${GREEN}代理已添加${NC}"
        sleep 1
        ;;

      2)
        if [[ ${#current_proxies[@]} -eq 0 ]]; then
          echo -e "${YELLOW}当前没有代理可删除${NC}"
          sleep 2
          continue
        fi

        read -p "请输入要删除的代理编号 [1-${#current_proxies[@]}]: " del_num

        if [[ "$del_num" =~ ^[0-9]+$ ]] && [[ "$del_num" -ge 1 ]] && [[ "$del_num" -le ${#current_proxies[@]} ]]; then
          # 删除指定索引的元素
          unset 'current_proxies[$((del_num-1))]'
          current_proxies=("${current_proxies[@]}")  # 重新索引数组
          save_proxy_list "${current_proxies[@]}"
          echo -e "${GREEN}代理已删除${NC}"
          sleep 1
        else
          echo -e "${RED}无效的编号${NC}"
          sleep 2
        fi
        ;;

      3)
        if [[ ${#current_proxies[@]} -eq 0 ]]; then
          echo -e "${YELLOW}当前没有代理${NC}"
          sleep 2
          continue
        fi

        read -p "确认清空所有代理? [y/N] " confirm
        case "$confirm" in
          [Yy]*)
            save_proxy_list
            echo -e "${GREEN}已清空所有代理${NC}"
            sleep 1
            ;;
          *)
            echo -e "${YELLOW}已取消${NC}"
            sleep 1
            ;;
        esac
        ;;

      4)
        if [[ ${#current_proxies[@]} -eq 0 ]]; then
          echo -e "\n${YELLOW}当前未配置代理，无法测速${NC}"
          echo -e "${YELLOW}请先添加代理后再进行测速对比${NC}"
          read -n 1 -s -r -p "按任意键继续..."
        else
          test_github_proxy_speed "${current_proxies[@]}"
        fi
        ;;

      0)
        return
        ;;

      *)
        echo -e "${RED}无效选择${NC}"
        sleep 1
        ;;
    esac
  done
}

# GitHub代理测速
test_github_proxy_speed() {
  local proxies=("$@")
  local test_url="https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt"

  echo -e "\n${CYAN}============== 代理测速对比 ==============${NC}"
  echo -e "${YELLOW}测试文件: version.txt${NC}\n"

  # 验证版本号格式 (x.y.z 或 vx.y.z)
  validate_version() {
    local content="$1"
    # 匹配版本号格式: 数字.数字.数字 (可选的v前缀)
    if [[ "$content" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      return 0
    else
      return 1
    fi
  }

  # 测试直连
  echo -e "${CYAN}测试 直连...${NC}"
  local start_time=$(date +%s%3N)
  local direct_result=$(curl -fsS --connect-timeout 5 --max-time 10 "$test_url" 2>/dev/null | tr -d '[:space:]')
  local end_time=$(date +%s%3N)
  local direct_time=$((end_time - start_time))

  if [[ -n "$direct_result" ]] && validate_version "$direct_result"; then
    echo -e "  ${GREEN}✓ 成功${NC} - 耗时: ${GREEN}${direct_time}ms${NC}"
    echo -e "  版本号: ${direct_result}"
  else
    if [[ -n "$direct_result" ]]; then
      echo -e "  ${RED}× 失败${NC} - 返回内容无效: ${direct_result}"
    else
      echo -e "  ${RED}× 失败${NC} - 连接超时或无法访问"
    fi
    direct_time=99999
  fi

  # 测试所有代理
  if [[ ${#proxies[@]} -eq 0 ]]; then
    echo -e "\n${YELLOW}当前未配置代理${NC}"
    read -n 1 -s -r -p "按任意键继续..."
    return
  fi

  echo ""
  local best_proxy=""
  local best_time=99999
  local proxy_times=()

  for proxy in "${proxies[@]}"; do
    echo -e "${CYAN}测试 ${proxy}${NC}"
    local proxy_url="${proxy}${test_url}"

    start_time=$(date +%s%3N)
    local proxy_result=$(curl -fsS --connect-timeout 5 --max-time 10 "$proxy_url" 2>/dev/null | tr -d '[:space:]')
    end_time=$(date +%s%3N)
    local proxy_time=$((end_time - start_time))

    if [[ -n "$proxy_result" ]] && validate_version "$proxy_result"; then
      echo -e "  ${GREEN}✓ 成功${NC} - 耗时: ${GREEN}${proxy_time}ms${NC}"
      echo -e "  版本号: ${proxy_result}"
      proxy_times+=("$proxy_time:$proxy")

      if [[ $proxy_time -lt $best_time ]]; then
        best_time=$proxy_time
        best_proxy=$proxy
      fi
    else
      if [[ -n "$proxy_result" ]]; then
        echo -e "  ${RED}× 失败${NC} - 返回内容无效: ${proxy_result}"
      else
        echo -e "  ${RED}× 失败${NC} - 连接超时或无法访问"
      fi
      proxy_times+=("99999:$proxy")
    fi
    echo ""
  done

  # 显示推荐
  echo -e "${CYAN}============== 测速结果 ==============${NC}"
  if [[ $direct_time -lt $best_time ]]; then
    echo -e "${GREEN}推荐: 直连${NC} (${direct_time}ms)"
    echo -e "${YELLOW}当前网络环境下，直连速度最快，建议清空代理${NC}"
  elif [[ $best_time -lt 99999 ]]; then
    echo -e "${GREEN}推荐: ${best_proxy}${NC} (${best_time}ms)"
    echo -e "${YELLOW}该代理速度最快，建议保留此代理${NC}"
  else
    echo -e "${RED}所有代理均无法访问${NC}"
    echo -e "${YELLOW}建议检查网络连接或更换其他代理${NC}"
  fi

  # 显示排序结果
  if [[ ${#proxy_times[@]} -gt 1 ]]; then
    echo -e "\n${CYAN}速度排行:${NC}"
    printf '%s\n' "${proxy_times[@]}" | sort -t: -k1 -n | while IFS=: read -r time proxy; do
      if [[ $time -eq 99999 ]]; then
        echo -e "  ${RED}× ${proxy}${NC} - 失败"
      else
        echo -e "  ${GREEN}✓ ${proxy}${NC} - ${time}ms"
      fi
    done
  fi

  read -n 1 -s -r -p "按任意键继续..."
}

# 保存代理列表
save_proxy_list() {
  local proxy_file="${CONFIG[BASE_DIR]}/github_proxy.txt"

  if [[ $# -eq 0 ]]; then
    # 清空代理
    rm -f "$proxy_file" 2>/dev/null
    CONFIG["GITHUB_PROXY"]=""
  else
    # 写入代理列表（一行一个）
    printf '%s\n' "$@" > "$proxy_file" 2>/dev/null || {
      echo -e "${RED}写入失败 (权限不足)${NC}"
      sleep 2
      return 1
    }
    # 保存到配置（用逗号分隔）
    CONFIG["GITHUB_PROXY"]=$(IFS=','; echo "$*")
  fi

  save_config

  # 提示重启容器
  local container_name=$(get_container_name)
  if docker ps --format '{{.Names}}' | grep -q "^${container_name}\$"; then
    read -p "代理配置已更新，是否重启容器使其生效? [Y/n] " yn
    case "$yn" in
      [Nn]*) ;;
      *) docker restart "$container_name" >/dev/null && echo -e "${GREEN}容器已重启${NC}" || echo -e "${RED}重启失败${NC}"
         sleep 1 ;;
    esac
  fi
}

# 数据库恢复
restore_database() {
  local backup_dir="${CONFIG[BASE_DIR]}/backup"

  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          数据库恢复          ${NC}"
  echo -e "${CYAN}=============================================${NC}"

  # 检查备份目录是否存在
  if [[ ! -d "$backup_dir" ]]; then
    echo -e "${YELLOW}备份目录不存在: $backup_dir${NC}"
    echo -e "${YELLOW}提示: 系统会在每天6点自动备份数据库到此目录${NC}"
    read -n 1 -s -r -p "按任意键继续..."
    return
  fi

  # 列出所有备份文件（按修改时间倒序，最新的在前）
  local backups=()
  while IFS= read -r file; do
    backups+=("$file")
  done < <(find "$backup_dir" -maxdepth 1 -type f -name "*.zip" -printf "%T@ %p\n" 2>/dev/null | sort -rn | cut -d' ' -f2-)

  if [[ ${#backups[@]} -eq 0 ]]; then
    echo -e "${YELLOW}未找到备份文件${NC}"
    echo -e "${YELLOW}提示: 备份文件应为 .zip 格式，保存在 $backup_dir 目录${NC}"
    read -n 1 -s -r -p "按任意键继续..."
    return
  fi

  echo -e "${YELLOW}可用的备份文件:${NC}\n"
  for i in "${!backups[@]}"; do
    local file="${backups[$i]}"
    local filename=$(basename "$file")
    local filesize=$(ls -lh "$file" | awk '{print $5}')
    local filetime=$(ls -l --time-style='+%Y-%m-%d %H:%M:%S' "$file" | awk '{print $6, $7}')
    echo -e " $((i+1)). ${GREEN}${filename}${NC}"
    echo -e "    大小: ${filesize}  时间: ${filetime}"
    echo ""
  done

  echo -e " 0. 取消"
  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "请选择要恢复的备份 [0-${#backups[@]}]: " choice

  # 验证输入
  if [[ "$choice" == "0" ]]; then
    return
  fi

  if ! [[ "$choice" =~ ^[0-9]+$ ]] || [[ "$choice" -lt 1 ]] || [[ "$choice" -gt ${#backups[@]} ]]; then
    echo -e "${RED}无效选择!${NC}"
    sleep 2
    return
  fi

  local selected_backup="${backups[$((choice-1))]}"
  local backup_name=$(basename "$selected_backup")

  echo -e "\n${RED}警告: 恢复操作将覆盖当前数据库!${NC}"
  read -p "确认恢复备份 ${backup_name}? [y/N] " confirm

  case "$confirm" in
    [Yy]*)
      echo -e "${CYAN}正在恢复数据库...${NC}"

      # 1. 复制备份文件到 database.zip
      if ! cp "$selected_backup" "${CONFIG[BASE_DIR]}/database.zip" 2>/dev/null; then
        echo -e "${RED}复制备份文件失败 (权限不足)${NC}"
        read -n 1 -s -r -p "按任意键继续..."
        return
      fi
      echo -e "${GREEN}✓ 备份文件已复制${NC}"

      # 2. 删除现有数据库文件
      rm -f "${CONFIG[BASE_DIR]}/atv.mv.db" 2>/dev/null && echo -e "${GREEN}✓ 已删除 atv.mv.db${NC}"
      rm -f "${CONFIG[BASE_DIR]}/atv.trace.db" 2>/dev/null && echo -e "${GREEN}✓ 已删除 atv.trace.db${NC}"

      # 3. 重启容器
      local container_name=$(get_container_name)
      if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
        echo -e "${YELLOW}正在重启容器...${NC}"
        if docker restart "$container_name" >/dev/null 2>&1; then
          echo -e "${GREEN}✓ 容器已重启${NC}"
          echo -e "\n${GREEN}数据库恢复完成!${NC}"
          echo -e "${YELLOW}提示: 容器正在初始化，请稍等片刻后访问管理界面${NC}"
        else
          echo -e "${RED}容器重启失败${NC}"
        fi
      else
        echo -e "${YELLOW}容器不存在，数据库文件已准备就绪${NC}"
        echo -e "${YELLOW}请通过菜单 '1. 安装/更新' 启动容器${NC}"
      fi
      ;;
    *)
      echo -e "${YELLOW}已取消恢复${NC}"
      ;;
  esac

  read -n 1 -s -r -p "按任意键继续..."
}

# 显示网络模式菜单
show_network_menu() {
  clear

  # 从配置文件重新加载，避免被 sync_runtime_config 覆盖的内存值
  local saved_network=""
  if [[ -f "$CONFIG_FILE" ]]; then
    saved_network=$(grep "^NETWORK=" "$CONFIG_FILE" 2>/dev/null | cut -d'=' -f2)
  fi
  [[ -n "$saved_network" ]] && CONFIG["NETWORK"]="$saved_network"

  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          网络模式设置          ${NC}"
  echo -e "${CYAN}=============================================${NC}"
  echo -e " 当前配置: ${GREEN}${CONFIG[NETWORK]}${NC}"

  # 显示容器实际使用的网络模式（如果存在）
  local container_name=$(get_container_name)
  if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q "^${container_name}\$"; then
    local actual_network=$(docker inspect --format '{{.HostConfig.NetworkMode}}' "$container_name" 2>/dev/null)
    if [[ -n "$actual_network" && "$actual_network" != "${CONFIG[NETWORK]}" ]]; then
      echo -e " 容器实际: ${YELLOW}${actual_network}${NC} (需要重建容器使配置生效)"
    fi
  fi

  echo -e " 1. bridge模式 (默认)"
  echo -e " 2. host模式"
  echo -e " 0. 返回"
  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "请选择网络模式 [0-2]: " choice

  case $choice in
    1)
      CONFIG["NETWORK"]="bridge"
      save_config
      echo -e "${GREEN}已设置为bridge模式${NC}"
      # 验证与当前镜像的兼容性
      if ! validate_image_network_compatibility; then
        CONFIG["NETWORK"]="host"
        save_config
        echo -e "${RED}当前镜像不兼容bridge模式，已回退${NC}"
        sleep 2
        return
      fi
      ;;
    2)
      CONFIG["NETWORK"]="host"
      save_config
      echo -e "${GREEN}已设置为host模式${NC}"
      # 验证与当前镜像的兼容性
      if ! validate_image_network_compatibility; then
        CONFIG["NETWORK"]="bridge"
        save_config
        echo -e "${RED}当前镜像不兼容host模式，已回退${NC}"
        sleep 2
        return
      fi
      ;;
    0)
      return
      ;;
    *)
      echo -e "${RED}无效选择!${NC}"
      ;;
  esac

  # 如果变更了网络模式且容器存在，提示需要重建
  if [[ "$choice" =~ ^[12]$ ]]; then
    if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
      echo -e "${YELLOW}注意: 网络模式变更需要重建容器才能生效${NC}"
      read -p "是否立即重建容器？[Y/n] " yn
      case "$yn" in
        [Nn]*) ;;
        *) recreate_container_for_changes ;;
      esac
    fi
  fi
}

# 显示重启策略菜单
show_restart_menu() {
  clear
  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          重启策略设置          ${NC}"
  echo -e "${CYAN}=============================================${NC}"
  echo -e " 当前重启策略: ${GREEN}${CONFIG[RESTART]}${NC}"
  echo -e " 1. always (总是重启)"
  echo -e " 2. unless-stopped (除非手动停止)"
  echo -e " 3. no (不自动重启)"
  echo -e " 0. 返回"
  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "请选择重启策略 [0-3]: " choice

  case $choice in
    1)
      CONFIG["RESTART"]="always"
      save_config
      echo -e "${GREEN}已设置为always${NC}"
      ;;
    2)
      CONFIG["RESTART"]="unless-stopped"
      save_config
      echo -e "${GREEN}已设置为unless-stopped${NC}"
      ;;
    3)
      CONFIG["RESTART"]="no"
      save_config
      echo -e "${GREEN}已设置为no${NC}"
      ;;
  esac

  if [[ "$choice" != "0" ]]; then
    sleep 1
  fi
}

# 显示配置管理菜单
show_config_menu() {
  while true; do
    clear

    # 从配置文件重新加载关键配置，避免被 sync_runtime_config 覆盖
    if [[ -f "$CONFIG_FILE" ]]; then
      while IFS='=' read -r key value; do
        if [[ -n "$key" && "$key" =~ ^(NETWORK|BASE_DIR|PORT1|PORT2|RESTART|MOUNT_WWW|GITHUB_PROXY)$ ]]; then
          CONFIG["$key"]="$value"
        fi
      done < "$CONFIG_FILE"
    fi

    echo -e "${CYAN}=============================================${NC}"
    echo -e "${GREEN}          当前配置管理          ${NC}"
    echo -e "${CYAN}=============================================${NC}"
    echo -e " 1. 数据目录: ${CONFIG[BASE_DIR]}"
    echo -e " 2. 管理端口: ${CONFIG[PORT1]}"
    echo -e " 3. AList端口: ${CONFIG[PORT2]}"
    echo -e " 4. 挂载/www目录: ${CONFIG[MOUNT_WWW]}"
    echo -e " 5. 自定义挂载目录"
    echo -e " 6. 网络模式: ${CONFIG[NETWORK]}"
    echo -e " 7. 重启策略: ${CONFIG[RESTART]}"
    echo -e " 8. 重置管理员密码"
    echo -e " 9. GitHub代理设置"
    echo -e " a. 数据恢复"
    echo -e " b. 立即备份数据库"
    echo -e " 0. 返回主菜单"
    echo -e "${CYAN}---------------------------------------------${NC}"
    read -p "选择要修改的配置 [0-9/a-b]: " config_choice

    local need_recreate=false

    case $config_choice in
      1)
        read -p "输入新的数据目录 [${CONFIG[BASE_DIR]}]: " new_dir
        if [[ -n "$new_dir" && "$new_dir" != "${CONFIG[BASE_DIR]}" ]]; then
          CONFIG[BASE_DIR]="$new_dir"
          save_config
          need_recreate=true
        fi
        ;;
      2)
        read -p "输入新的管理端口 [${CONFIG[PORT1]}]: " new_port
        if [[ -z "$new_port" ]]; then
            continue
        fi
        if ! [[ "$new_port" =~ ^[0-9]+$ ]]; then
            echo -e "${RED}端口号必须是数字!${NC}"
            sleep 1
            continue
        fi
        if [[ "$new_port" != "${CONFIG[PORT1]}" ]]; then
          CONFIG[PORT1]="$new_port"
          save_config
          need_recreate=true
        fi
        ;;
      3)
        read -p "输入新的AList端口 [${CONFIG[PORT2]}]: " new_port
        if [[ -z "$new_port" ]]; then
            continue
        fi
        if ! [[ "$new_port" =~ ^[0-9]+$ ]]; then
            echo -e "${RED}端口号必须是数字!${NC}"
            sleep 1
            continue
        fi
        if [[ "$new_port" != "${CONFIG[PORT2]}" ]]; then
          CONFIG[PORT2]="$new_port"
          save_config
          need_recreate=true
        fi
        ;;
      4)
        if [[ "${CONFIG[MOUNT_WWW]}" == "true" ]]; then
          CONFIG["MOUNT_WWW"]="false"
        else
          CONFIG["MOUNT_WWW"]="true"
        fi
        save_config
        need_recreate=true
        ;;
      5)
        manage_custom_mounts
        # manage_custom_mounts内部已处理重建逻辑
        continue
        ;;
      6)
        show_network_menu
        # show_network_menu 内部已处理重建逻辑
        continue
        ;;
      7)
        show_restart_menu
        save_config
        # 重启策略修改不需要重建容器
        docker update --restart="${CONFIG[RESTART]}" $(get_container_name) >/dev/null 2>&1
        continue
        ;;
      8)
        reset_admin_password
        # 密码重置已包含重启逻辑
        continue
        ;;
      9)
        manage_github_proxy
        continue
        ;;
      a|A)
        restore_database
        continue
        ;;
      b|B)
        backup_database_now
        continue
        ;;
      0)
        break
        ;;
    esac

    if [[ "$need_recreate" == "true" ]]; then
      recreate_container_for_changes
    fi
  done
}

# 主循环



# =========================
# ENVIRONMENT INIT / HEALTH
# =========================



is_default_or_legacy_base_dir() {
  local dir="${1:-}"
  # 仅把 /etc/xiaoya（旧版默认路径）和空值视为“未选定”，允许重新探测；
  # 其它路径一律视为用户已选定，避免把用户数据目录改写后孤立其数据。
  [[ -z "$dir" || "$dir" == "/etc/xiaoya" ]]
}

check_port_conflict() {
  local ports=()

  if [[ "${CONFIG[NETWORK]:-bridge}" == "host" ]]; then
    ports=(4567 5678 5233 5234)
  else
    ports=("${CONFIG[PORT1]:-4567}" "${CONFIG[PORT2]:-5344}")
  fi

  local p
  for p in "${ports[@]}"; do
    if command -v ss >/dev/null 2>&1 && ss -lnt 2>/dev/null | awk '{print $4}' | grep -Eq "(:|\.)${p}$"; then
      echo -e "${YELLOW}警告：端口 $p 已被占用，容器启动可能失败${NC}"
    elif command -v netstat >/dev/null 2>&1 && netstat -lnt 2>/dev/null | awk '{print $4}' | grep -Eq "(:|\.)${p}$"; then
      echo -e "${YELLOW}警告：端口 $p 已被占用，容器启动可能失败${NC}"
    fi
  done
}

init_environment() {
  local allow_mutation="${1:-false}"

  if [[ "$allow_mutation" != "true" ]]; then
    check_port_conflict
    return 0
  fi

  # 只在 install/update/menu 等写操作路径中修正旧路径；
  # status/logs/health 等只读命令不应改写 app.conf。
  if is_default_or_legacy_base_dir "${CONFIG[BASE_DIR]:-}"; then
    CONFIG["BASE_DIR"]="$(detect_best_base_dir)"
    save_config
  fi

  check_port_conflict
}


check_url() {
  local name="$1"
  local url="$2"

  if curl -fsS --connect-timeout 3 "$url" >/dev/null 2>&1; then
    echo -e "${name}: ${GREEN}OK${NC} ${url}"
  else
    echo -e "${name}: ${RED}FAIL${NC} ${url}"
  fi
}

health_check() {
  sync_runtime_config || true
  local ip
  ip="$(get_host_ip)"
  ip="${ip:-localhost}"

  echo -e "${CYAN}============== 健康检查 ==============${NC}"

  # 根据镜像名称判断是否为xiaoya host系列
  local image_name="${CONFIG[IMAGE_NAME]:-}"
  if [[ "$image_name" =~ xiaoya.*host ]]; then
    # xiaoya host系列镜像的健康检查（特殊端口5234）
    check_url "管理应用" "http://${ip}:4567/"
    check_url "Nginx" "http://${ip}:5678/"
    check_url "httpd" "http://${ip}:5233/"
    check_url "AList" "http://${ip}:5234/"
  elif [[ "${CONFIG[NETWORK]:-bridge}" == "host" ]]; then
    # 所有其他镜像的host模式（标准端口5244）
    check_url "管理应用" "http://${ip}:4567/"
    check_url "AList" "http://${ip}:5244/"
  else
    # bridge模式（所有镜像）
    check_url "管理应用" "http://${ip}:${CONFIG[PORT1]:-4567}/"
    check_url "入口服务" "http://${ip}:${CONFIG[PORT2]:-5344}/"
  fi
}

# 检查端口是否被占用
check_port_in_use() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -lnt 2>/dev/null | awk '{print $4}' | grep -Eq "(:|\.)${port}$"
  elif command -v netstat >/dev/null 2>&1; then
    netstat -lnt 2>/dev/null | awk '{print $4}' | grep -Eq "(:|\.)${port}$"
  else
    return 1
  fi
}

# 网络诊断
network_diagnostics() {
  echo -e "\n${CYAN}============== 网络诊断 ==============${NC}"

  # 1. 测试外网连通性
  echo -e "${CYAN}1. 外网连通性测试${NC}"
  local test_sites=("1.1.1.1" "8.8.8.8" "114.114.114.114")
  local connectivity_ok=false

  for site in "${test_sites[@]}"; do
    if ping -c 1 -W 2 "$site" >/dev/null 2>&1; then
      echo -e "  ${GREEN}✓${NC} $site 连接正常"
      connectivity_ok=true
      break
    else
      echo -e "  ${RED}×${NC} $site 无法连接"
    fi
  done

  if [[ "$connectivity_ok" == "false" ]]; then
    echo -e "  ${RED}× 外网连接失败，请检查网络配置${NC}"
    return 1
  fi

  # 2. DNS解析测试
  echo -e "\n${CYAN}2. DNS解析测试${NC}"
  local dns_test_domains=("www.baidu.com" "github.com" "docker.io")
  local dns_ok=false

  for domain in "${dns_test_domains[@]}"; do
    if nslookup "$domain" >/dev/null 2>&1 || host "$domain" >/dev/null 2>&1; then
      echo -e "  ${GREEN}✓${NC} $domain 解析成功"
      dns_ok=true
    else
      echo -e "  ${YELLOW}⚠${NC} $domain 解析失败"
    fi
  done

  if [[ "$dns_ok" == "false" ]]; then
    echo -e "  ${RED}× DNS解析失败，请检查DNS配置${NC}"
    echo -e "  ${YELLOW}建议: 修改 /etc/resolv.conf 添加公共DNS${NC}"
  fi

  # 3. GitHub连通性测试（含代理）
  echo -e "\n${CYAN}3. GitHub连通性测试${NC}"

  # 测试直连
  if curl -fsS --connect-timeout 3 https://github.com >/dev/null 2>&1; then
    echo -e "  ${GREEN}✓${NC} GitHub直连正常"
  else
    echo -e "  ${RED}×${NC} GitHub直连失败"

    # 测试配置的代理
    local proxy_file="${CONFIG[BASE_DIR]}/github_proxy.txt"
    if [[ -f "$proxy_file" && -s "$proxy_file" ]]; then
      echo -e "  ${CYAN}测试配置的GitHub代理:${NC}"
      while IFS= read -r proxy; do
        [[ -z "$proxy" ]] && continue
        local test_url="${proxy}https://github.com"
        if curl -fsS --connect-timeout 3 "$test_url" >/dev/null 2>&1; then
          echo -e "    ${GREEN}✓${NC} $proxy"
        else
          echo -e "    ${RED}×${NC} $proxy"
        fi
      done < "$proxy_file"
    else
      echo -e "  ${YELLOW}提示: 可通过配置管理添加GitHub代理${NC}"
    fi
  fi

  # 4. Docker Hub连通性测试
  echo -e "\n${CYAN}4. Docker Hub连通性测试${NC}"
  if curl -fsS --connect-timeout 3 https://hub.docker.com >/dev/null 2>&1; then
    echo -e "  ${GREEN}✓${NC} Docker Hub连接正常"
  else
    echo -e "  ${RED}×${NC} Docker Hub连接失败"
    echo -e "  ${YELLOW}建议: 配置Docker镜像加速${NC}"
  fi

  # 5. 容器网络测试（如果容器运行中）
  local container_name=$(get_container_name)
  if docker ps --format '{{.Names}}' | grep -q "^${container_name}\$"; then
    echo -e "\n${CYAN}5. 容器网络测试${NC}"

    # 测试容器内网络
    if docker exec "$container_name" ping -c 1 -W 2 1.1.1.1 >/dev/null 2>&1; then
      echo -e "  ${GREEN}✓${NC} 容器内网络正常"
    else
      echo -e "  ${RED}×${NC} 容器内网络异常"
    fi

    # 测试容器DNS
    if docker exec "$container_name" nslookup www.baidu.com >/dev/null 2>&1; then
      echo -e "  ${GREEN}✓${NC} 容器DNS正常"
    else
      echo -e "  ${YELLOW}⚠${NC} 容器DNS可能异常"
    fi
  fi

  echo -e "\n${GREEN}网络诊断完成${NC}"
}

auto_repair() {
  local container_name
  container_name="$(get_container_name)"

  echo -e "${CYAN}============== 自动修复诊断 ==============${NC}"

  # 1. 检查容器是否存在
  if ! docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
    echo -e "${RED}× 容器不存在${NC}"
    echo -e "${YELLOW}建议：选择菜单 '1. 安装/更新' 创建容器${NC}"
    read -n 1 -s -r -p "按任意键继续..."
    return 1
  fi

  echo -e "${GREEN}✓ 容器存在${NC}"

  # 2. 检查容器运行状态
  local running
  running="$(docker inspect -f '{{.State.Running}}' "$container_name" 2>/dev/null || echo false)"

  if [[ "$running" != "true" ]]; then
    echo -e "${RED}× 容器未运行${NC}"

    # 检查退出状态
    local exit_code
    local exit_error
    exit_code="$(docker inspect -f '{{.State.ExitCode}}' "$container_name" 2>/dev/null || echo 0)"
    exit_error="$(docker inspect -f '{{.State.Error}}' "$container_name" 2>/dev/null || echo '')"

    if [[ "$exit_code" != "0" ]]; then
      echo -e "${YELLOW}  容器异常退出 (退出码: $exit_code)${NC}"
      [[ -n "$exit_error" ]] && echo -e "${YELLOW}  错误: $exit_error${NC}"
    fi

    # 检查端口冲突
    echo -e "${CYAN}  检查端口冲突...${NC}"
    local has_conflict=false
    if [[ "${CONFIG[NETWORK]}" == "host" ]]; then
      for p in 4567 5678 5233 5234; do
        if check_port_in_use "$p"; then
          echo -e "${RED}  × 端口 $p 被占用${NC}"
          has_conflict=true
        fi
      done
    else
      if check_port_in_use "${CONFIG[PORT1]}"; then
        echo -e "${RED}  × 管理端口 ${CONFIG[PORT1]} 被占用${NC}"
        has_conflict=true
      fi
      if check_port_in_use "${CONFIG[PORT2]}"; then
        echo -e "${RED}  × AList端口 ${CONFIG[PORT2]} 被占用${NC}"
        has_conflict=true
      fi
    fi

    if [[ "$has_conflict" == "true" ]]; then
      echo -e "${YELLOW}建议：通过菜单 '8. 配置管理' 修改端口，或停止占用端口的进程${NC}"
      read -n 1 -s -r -p "按任意键继续..."
      return 1
    fi
    echo -e "${GREEN}  ✓ 端口无冲突${NC}"

    # 检查数据目录权限
    if [[ ! -w "${CONFIG[BASE_DIR]}" ]]; then
      echo -e "${RED}  × 数据目录无写权限: ${CONFIG[BASE_DIR]}${NC}"
      echo -e "${YELLOW}  尝试修复权限...${NC}"
      if chmod 755 "${CONFIG[BASE_DIR]}" 2>/dev/null; then
        echo -e "${GREEN}  ✓ 权限已修复${NC}"
      else
        echo -e "${RED}  × 权限修复失败${NC}"
        echo -e "${YELLOW}  请手动执行: sudo chmod 755 ${CONFIG[BASE_DIR]}${NC}"
        read -n 1 -s -r -p "按任意键继续..."
        return 1
      fi
    else
      echo -e "${GREEN}  ✓ 数据目录权限正常${NC}"
    fi

    # 尝试启动容器
    echo -e "${YELLOW}  正在启动容器...${NC}"
    if docker start "$container_name" >/dev/null 2>&1; then
      echo -e "${GREEN}✓ 容器已启动${NC}"
      echo -e "${CYAN}等待服务初始化...${NC}"
      sleep 3
    else
      echo -e "${RED}× 容器启动失败${NC}"
      echo -e "${YELLOW}可能的原因：${NC}"
      echo -e "  1. 配置文件损坏"
      echo -e "  2. 镜像与网络模式不匹配"
      echo -e "  3. 挂载目录问题"
      echo ""
      read -p "是否尝试重建容器以修复？[y/N] " yn
      case "$yn" in
        [Yy]*)
          echo -e "${YELLOW}正在重建容器...${NC}"
          docker rm -f "$container_name" >/dev/null
          start_container
          echo -e "${GREEN}✓ 容器已重建并启动${NC}"
          echo -e "${CYAN}等待服务初始化...${NC}"
          sleep 3
          ;;
        *)
          echo -e "${YELLOW}已取消重建${NC}"
          echo -e "${YELLOW}请查看日志排查问题: docker logs $container_name${NC}"
          read -n 1 -s -r -p "按任意键继续..."
          return 1
          ;;
      esac
    fi
  else
    echo -e "${GREEN}✓ 容器正在运行${NC}"
  fi

  # 3. 检查容器健康状态
  echo -e "\n${CYAN}============== 服务健康检查 ==============${NC}"
  health_check

  # 4. 网络诊断
  network_diagnostics

  echo -e "\n${GREEN}诊断完成${NC}"
  read -n 1 -s -r -p "按任意键继续..."
}

# 清理Docker资源
cleanup_docker_resources() {
  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          Docker资源清理          ${NC}"
  echo -e "${CYAN}=============================================${NC}"

  # 显示当前磁盘使用情况
  echo -e "${YELLOW}正在分析Docker磁盘使用情况...${NC}\n"
  docker system df 2>/dev/null || {
    echo -e "${RED}无法获取Docker磁盘信息${NC}"
    read -n 1 -s -r -p "按任意键继续..."
    return
  }

  echo -e "\n${CYAN}可执行的清理操作:${NC}"
  echo -e " 1. 清理未使用的镜像"
  echo -e " 2. 清理已停止的容器"
  echo -e " 3. 清理未使用的数据卷"
  echo -e " 4. 清理构建缓存"
  echo -e " 5. 一键清理所有未使用资源"
  echo -e " 0. 返回"
  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "请选择清理操作 [0-5]: " choice

  case $choice in
    1)
      echo -e "${YELLOW}正在清理未使用的镜像...${NC}"
      docker image prune -a -f
      echo -e "${GREEN}✓ 清理完成${NC}"
      ;;
    2)
      echo -e "${YELLOW}正在清理已停止的容器...${NC}"
      docker container prune -f
      echo -e "${GREEN}✓ 清理完成${NC}"
      ;;
    3)
      echo -e "${YELLOW}正在清理未使用的数据卷...${NC}"
      docker volume prune -f
      echo -e "${GREEN}✓ 清理完成${NC}"
      ;;
    4)
      echo -e "${YELLOW}正在清理构建缓存...${NC}"
      docker builder prune -a -f
      echo -e "${GREEN}✓ 清理完成${NC}"
      ;;
    5)
      echo -e "${RED}警告: 此操作将清理所有未使用的Docker资源${NC}"
      read -p "确认继续? [y/N] " confirm
      case "$confirm" in
        [Yy]*)
          echo -e "${YELLOW}正在清理...${NC}"
          docker system prune -a -f --volumes
          echo -e "${GREEN}✓ 清理完成${NC}"
          ;;
        *)
          echo -e "${YELLOW}已取消${NC}"
          ;;
      esac
      ;;
    0)
      return
      ;;
    *)
      echo -e "${RED}无效选择${NC}"
      ;;
  esac

  # 显示清理后的磁盘使用情况
  if [[ "$choice" =~ ^[1-5]$ && "$choice" != "0" ]]; then
    echo -e "\n${CYAN}清理后磁盘使用情况:${NC}"
    docker system df 2>/dev/null
  fi

  read -n 1 -s -r -p "按任意键继续..."
}

# 打开Web界面
open_web_interface() {
  sync_runtime_config || true
  local ip=$(get_host_ip)

  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          打开Web界面          ${NC}"
  echo -e "${CYAN}=============================================${NC}"

  local manage_url admin_url
  if [[ "${CONFIG[NETWORK]}" == "host" ]]; then
    manage_url="http://${ip}:4567/"
    admin_url="http://${ip}:5234/"
  else
    manage_url="http://${ip}:${CONFIG[PORT1]}/"
    admin_url="http://${ip}:${CONFIG[PORT2]}/"
  fi

  echo -e " 1. 管理界面: ${GREEN}${manage_url}${NC}"
  echo -e " 2. AList界面: ${GREEN}${admin_url}${NC}"
  echo -e " 0. 返回"
  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "请选择要打开的界面 [0-2]: " choice

  local target_url=""
  case $choice in
    1) target_url="$manage_url" ;;
    2) target_url="$admin_url" ;;
    0) return ;;
    *)
      echo -e "${RED}无效选择${NC}"
      sleep 1
      return
      ;;
  esac

  echo -e "${CYAN}正在打开浏览器...${NC}"

  # 尝试多种方式打开浏览器
  if command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$target_url" 2>/dev/null &
    echo -e "${GREEN}✓ 已在浏览器中打开${NC}"
  elif command -v open >/dev/null 2>&1; then
    open "$target_url" 2>/dev/null &
    echo -e "${GREEN}✓ 已在浏览器中打开${NC}"
  elif command -v start >/dev/null 2>&1; then
    start "$target_url" 2>/dev/null &
    echo -e "${GREEN}✓ 已在浏览器中打开${NC}"
  else
    echo -e "${YELLOW}无法自动打开浏览器${NC}"
    echo -e "${YELLOW}请手动访问: ${target_url}${NC}"
  fi

  sleep 2
}

# 日志管理
manage_logs() {
  local container_name=$(get_container_name)

  while true; do
    clear
    echo -e "${CYAN}=============================================${NC}"
    echo -e "${GREEN}          日志管理          ${NC}"
    echo -e "${CYAN}=============================================${NC}"
    echo -e " 1. 实时查看日志 (Ctrl+C退出)"
    echo -e " 2. 查看最近N行"
    echo -e " 3. 按关键词过滤"
    echo -e " 4. 导出日志文件"
    echo -e " 5. 查看日志文件大小"
    echo -e " 0. 返回主菜单"
    echo -e "${CYAN}---------------------------------------------${NC}"
    read -p "请选择操作 [0-5]: " log_choice

    case $log_choice in
      1)
        echo -e "${CYAN}实时查看日志 (按 Ctrl+C 退出)...${NC}"
        sleep 1
        docker logs -f "$container_name" 2>&1
        ;;
      2)
        read -p "请输入要查看的行数 [默认100]: " lines
        lines=${lines:-100}
        if [[ "$lines" =~ ^[0-9]+$ ]]; then
          if command -v less >/dev/null 2>&1; then
            # 有 less 时使用分页器
            docker logs --tail "$lines" "$container_name" 2>&1 | less -R
          else
            # 没有 less 时使用 more 或直接输出（限制行数）
            if command -v more >/dev/null 2>&1; then
              docker logs --tail "$lines" "$container_name" 2>&1 | more
            else
              echo -e "${CYAN}显示最近 ${lines} 行日志:${NC}\n"
              docker logs --tail "$lines" "$container_name" 2>&1
              read -n 1 -s -r -p "按任意键继续..."
            fi
          fi
        else
          echo -e "${RED}无效的行数${NC}"
          sleep 1
        fi
        ;;
      3)
        read -p "请输入关键词: " keyword
        if [[ -n "$keyword" ]]; then
          echo -e "${CYAN}包含 '${keyword}' 的日志:${NC}\n"

          if command -v less >/dev/null 2>&1; then
            # 有 less 时使用分页器
            docker logs "$container_name" 2>&1 | grep --color=always -i "$keyword" | less -R
          elif command -v more >/dev/null 2>&1; then
            # 有 more 时使用它
            docker logs "$container_name" 2>&1 | grep --color=always -i "$keyword" | more
          else
            # 都没有时，限制显示前200行
            local result=$(docker logs "$container_name" 2>&1 | grep --color=always -i "$keyword")
            local line_count=$(echo "$result" | wc -l)

            if [[ $line_count -gt 200 ]]; then
              echo -e "${YELLOW}找到 ${line_count} 行匹配结果，显示前200行:${NC}\n"
              echo "$result" | head -n 200
              echo -e "\n${YELLOW}...省略 $((line_count - 200)) 行，建议使用选项4导出完整日志${NC}"
            else
              echo "$result"
            fi
            read -n 1 -s -r -p "按任意键继续..."
          fi
        else
          echo -e "${RED}关键词不能为空${NC}"
          sleep 1
        fi
        ;;
      4)
        local export_dir="${CONFIG[BASE_DIR]}/logs"
        mkdir -p "$export_dir" 2>/dev/null || {
          echo -e "${RED}无法创建导出目录 (权限不足)${NC}"
          read -n 1 -s -r -p "按任意键继续..."
          continue
        }

        local timestamp=$(date +"%Y%m%d-%H%M%S")
        local log_file="${export_dir}/${container_name}-${timestamp}.log"

        echo -e "${CYAN}正在导出日志...${NC}"
        if docker logs "$container_name" > "$log_file" 2>&1; then
          local filesize=$(ls -lh "$log_file" | awk '{print $5}')
          echo -e "${GREEN}✓ 日志已导出${NC}"
          echo -e "文件位置: ${GREEN}${log_file}${NC}"
          echo -e "文件大小: ${filesize}"
        else
          echo -e "${RED}导出失败${NC}"
        fi
        read -n 1 -s -r -p "按任意键继续..."
        ;;
      5)
        echo -e "${CYAN}正在获取日志大小...${NC}"
        local log_info=$(docker inspect --format='{{.LogPath}}' "$container_name" 2>/dev/null)
        if [[ -n "$log_info" && -f "$log_info" ]]; then
          local log_size=$(ls -lh "$log_info" | awk '{print $5}')
          echo -e "日志文件: ${YELLOW}${log_info}${NC}"
          echo -e "文件大小: ${GREEN}${log_size}${NC}"
          echo -e "\n${YELLOW}提示: Docker日志占用磁盘空间过大时，可通过配置日志轮转限制大小${NC}"
        else
          echo -e "${RED}无法获取日志信息${NC}"
        fi
        read -n 1 -s -r -p "按任意键继续..."
        ;;
      0)
        return
        ;;
      *)
        echo -e "${RED}无效选择${NC}"
        sleep 1
        ;;
    esac
  done
}

interactive_mode() {
  check_environment
  load_config
  init_environment true

  while true; do
    show_menu
    local container_name=$(get_container_name)
    local status=$(check_container_status)

    case $choice in
      1)
        install_container
        ;;
      2)
        case "$status" in
          "running")
            echo "停止容器..."
            docker stop "$container_name"
            echo -e "${GREEN}容器已停止${NC}"
            ;;
          *)
            echo "启动容器..."
            if docker start "$container_name" 2>/dev/null; then
              echo -e "${GREEN}容器已启动${NC}"
            else
              echo -e "${RED}启动失败，容器不存在${NC}"
              read -p "是否立即安装容器？[Y/n] " yn
              case $yn in
                [Nn]* ) ;;
                * ) install_container;;
              esac
            fi
            ;;
        esac
        sleep 1
        ;;
      3)
        if [ "$status" != "not_exist" ]; then
          echo "重启容器..."
          docker restart "$container_name"
          echo -e "${GREEN}容器已重启${NC}"
        else
          echo -e "${RED}容器不存在，请先安装${NC}"
        fi
        sleep 1
        ;;
      4)
        check_status
        ;;
      5)
        if [ "$status" != "not_exist" ]; then
          manage_logs
        else
          echo -e "${YELLOW}容器不存在${NC}"
          sleep 1
        fi
        ;;
      6)
        if [ "$status" != "not_exist" ]; then
          echo -e "${YELLOW}警告: 此操作将删除容器，但不会删除数据目录${NC}"
          echo -e "${YELLOW}数据目录: ${CONFIG[BASE_DIR]}${NC}"
          read -p "确认卸载容器? (y/N): " confirm
          if [[ "$confirm" =~ ^[Yy]$ ]]; then
            echo "正在卸载容器..."
            docker rm -f "$container_name"
            echo -e "${GREEN}容器已卸载${NC}"
            echo -e "${CYAN}数据目录已保留，如需删除请手动清理${NC}"
          else
            echo -e "${CYAN}已取消卸载${NC}"
          fi
        else
          echo -e "${YELLOW}容器不存在${NC}"
        fi
        sleep 2
        ;;
      7)
        show_version_menu
        ;;
      8)
        show_config_menu
        ;;
      9)
        auto_repair
        ;;
      c|C)
        cleanup_docker_resources
        ;;
      w|W)
        open_web_interface
        ;;
      0)
        echo -e "${GREEN}再见!${NC}"
        exit 0
        ;;
      *)
        echo -e "${RED}无效选项!${NC}"
        sleep 1
        ;;
    esac
  done
}


is_safe_data_dir() {
  local dir="${1:-}"

  [[ -n "$dir" ]] || return 1
  [[ "$dir" != "/" ]] || return 1
  [[ "$dir" != "/home" && "$dir" != "/etc" && "$dir" != "/opt" && "$dir" != "/usr" && "$dir" != "/var" ]] || return 1
  [[ "$dir" != "$HOME" ]] || return 1

  case "$dir" in
    /opt/alist-tvbox|/opt/alist-tvbox/*) return 0 ;;
    /volume*/docker/alist-tvbox|/volume*/docker/alist-tvbox/*) return 0 ;;
    /share/CACHEDEV*_DATA/docker/alist-tvbox|/share/CACHEDEV*_DATA/docker/alist-tvbox/*) return 0 ;;
    "$PWD"/alist-tvbox|"$PWD"/alist-tvbox/*) return 0 ;;
    *) return 1 ;;
  esac
}

confirm_delete_data_dir() {
  local dir="$1"
  echo -e "${RED}危险操作：将删除数据目录：$dir${NC}"
  read -p "请输入 DELETE 确认删除: " confirm
  [[ "$confirm" == "DELETE" ]]
}

# 命令行模式处理
cli_mode() {
  check_environment
  load_config
  local container_name=$(get_container_name)

  case "$1" in
    install)
      init_environment true
      install_container
      ;;
    start)
      echo "启动容器..."
      docker start "$container_name" || {
        echo -e "${RED}启动失败，容器不存在${NC}"
        exit 1
      }
      ;;
    stop)
      echo "停止容器..."
      docker stop "$container_name" || {
        echo -e "${RED}停止失败，容器不存在${NC}"
        exit 1
      }
      ;;
    restart)
      echo "重启容器..."
      docker restart "$container_name" || {
        echo -e "${RED}重启失败，容器不存在${NC}"
        exit 1
      }
      ;;
    status)
      check_status
      ;;
    logs)
      local param=""
      if [[ "$#" -ge 2 && "$2" == "-f" ]]; then
       param="-f"
      fi
      docker logs $param "$container_name" || {
        echo -e "${RED}容器不存在${NC}"
        exit 1
      }
      ;;
    uninstall)
      docker rm -f "$container_name" || {
        echo -e "${RED}容器不存在${NC}"
      }
      if [[ "$#" -ge 2 && "$2" == "-f" ]]; then
       if is_safe_data_dir "${CONFIG[BASE_DIR]}" && confirm_delete_data_dir "${CONFIG[BASE_DIR]}"; then
         echo -e "${RED}删除安装目录：${CONFIG[BASE_DIR]}${NC}"
         rm -rf -- "${CONFIG[BASE_DIR]}"
       else
         echo -e "${YELLOW}已取消删除数据目录，或目录不在安全白名单内：${CONFIG[BASE_DIR]}${NC}"
       fi
      fi
      ;;
    update)
      init_environment true
      if [[ "$#" -ge 2 && "$2" == "-y" ]]; then
        check_update "-y"
      else
        check_update
      fi
      ;;
    health)
      health_check
      ;;
    repair)
      auto_repair
      ;;
    menu)
      interactive_mode
      ;;
    *)
      echo -e "${RED}未知命令: $1${NC}"
      echo "可用命令: install, start, stop, restart, status, logs, uninstall, update, health, repair, menu"
      exit 1
      ;;
  esac
}

main() {
  if [ $# -eq 0 ]; then
    interactive_mode
  else
    cli_mode "$@"
  fi
}

if [[ "${ALIST_TVBOX_SOURCE_ONLY:-}" != "1" ]]; then
  main "$@"
fi
