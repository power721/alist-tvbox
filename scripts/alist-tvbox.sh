#!/usr/bin/env bash
set -euo pipefail

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
  ["1"]="haroldli/alist-tvbox              - 纯净版（推荐）"
  ["2"]="haroldli/alist-tvbox:native       - 纯净原生版"
  ["3"]="haroldli/alist-tvbox:python       - 纯净版（Python运行环境）"
  ["4"]="haroldli/xiaoya-tvbox             - 小雅集成版（推荐）"
  ["5"]="haroldli/xiaoya-tvbox:native      - 小雅原生版"
  ["6"]="haroldli/xiaoya-tvbox:native-host - 小雅原生主机版"
  ["7"]="haroldli/xiaoya-tvbox:host        - 小雅主机模式版"
  ["8"]="haroldli/xiaoya-tvbox:python      - 小雅版（Python运行环境）"
  ["9"]="haroldli/xiaoya-tvbox:dev         - 开发测试版"
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

# 启动容器
start_container() {
  local image="${CONFIG[IMAGE_NAME]}"
  local container_name=$(get_container_name)
  local aList_port=80

  # 确保数据目录存在
  mkdir -p "${CONFIG[BASE_DIR]}"

  if [[ -n "${CONFIG[GITHUB_PROXY]}" ]]; then
    echo "${CONFIG[GITHUB_PROXY]}" > "${CONFIG[BASE_DIR]}/github_proxy.txt"
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
    -v tvbox-www-static:/www/static
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
  echo -e "${YELLOW} Docker Server: ${docker_version}${NC}"
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
  echo -e "${GREEN} 5. 查看日志${NC}"
  echo -e "${GREEN} 6. 卸载容器${NC}"
  echo -e "${GREEN} 7. 选择版本${NC}"
  echo -e "${GREEN} 8. 配置管理${NC}"
  echo -e "${GREEN} 9. 检查更新${NC}"
  echo -e "${GREEN} h. 健康检查${NC}"
  echo -e "${GREEN} r. 自动修复${NC}"
  echo -e "${GREEN} 0. 退出${NC}"
  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "请输入选项 [0-9/h/r]: " choice
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

# 安装/更新容器

# -------------------------
# LEGACY MIGRATION (v3)
# -------------------------
migrate_legacy_data() {
  local legacy_dir="/etc/xiaoya"
  local new_dir="${CONFIG[BASE_DIR]:-/opt/alist-tvbox}"

  mkdir -p "$new_dir"

  if [[ -f "$new_dir/.v3" ]]; then
    echo -e "${GREEN}数据已迁移，跳过${NC}"
    return 0
  fi

  # 目标目录已有数据则不覆盖，直接标记完成
  if [[ -n "$(ls -A "$new_dir" 2>/dev/null)" ]]; then
    touch "$new_dir/.v3"
    echo -e "${GREEN}目标目录已有数据，跳过迁移${NC}"
    return 0
  fi

  # 从现存容器的 /data 挂载反推用户实际使用的数据目录。
  # 只有当现存容器确实把 /data 绑定到 /etc/xiaoya 时才迁移，
  # 不假设用户数据一定在 /etc/xiaoya。
  local bound_source=""
  local name
  for name in "$(get_container_name)" "$(get_opposite_container_name)"; do
    [[ -n "$name" ]] || continue
    if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q "^${name}\$"; then
      bound_source="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}' "$name" 2>/dev/null)"
      [[ -n "$bound_source" ]] && break
    fi
  done

  if [[ "$bound_source" == "$legacy_dir" && -d "$legacy_dir" && "$new_dir" != "$legacy_dir" ]]; then
    echo -e "${YELLOW}检测到容器数据目录为旧路径，正在迁移: $legacy_dir -> $new_dir${NC}"
    if cp -a "$legacy_dir/." "$new_dir/"; then
      touch "$new_dir/.v3"
      echo -e "${GREEN}迁移完成${NC}"
    else
      # 清理半成品，避免下次因目标非空被跳过而残留不完整数据
      if [[ -n "$new_dir" && "$new_dir" != "/" ]]; then
        find "$new_dir" -mindepth 1 -delete 2>/dev/null || true
      fi
      echo -e "${RED}迁移失败：已清理目标目录，请检查权限（可能需要 root）后重试，或手动复制 $legacy_dir -> $new_dir${NC}"
      return 1
    fi
  else
    touch "$new_dir/.v3"
    echo -e "${GREEN}未检测到绑定 /etc/xiaoya 的容器，无需迁移${NC}"
  fi
}


install_container() {
  migrate_legacy_data
  # 先检查架构支持
  if ! check_architecture_support; then
    return 1
  fi

  # 如果镜像名称包含host，自动切换网络模式
  if [[ "${CONFIG[IMAGE_NAME]}" == *"host"* ]]; then
    CONFIG["NETWORK"]="host"
    echo -e "${YELLOW}检测到host版本，已自动切换网络模式为host${NC}"
    save_config
  fi

  local container_name=$(get_container_name)
  remove_opposite_container

  INIT=false
  # 检查基础目录是否存在
  if [[ ! -d "${CONFIG[BASE_DIR]}" ]]; then
    echo -e "${YELLOW}基础目录不存在，正在创建: ${CONFIG[BASE_DIR]}${NC}"
    mkdir -p "${CONFIG[BASE_DIR]}"
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
    local image="${VERSIONS[$version_choice]}"
    image=$(echo "$image" | awk -F' - ' '{print $1}' | tr -d ' ')
    CONFIG["IMAGE_ID"]="$version_choice"
    CONFIG["IMAGE_NAME"]="${image}"

    # 新增：如果镜像名称包含host，自动切换网络模式
    if [[ "${image}" == *"host"* ]]; then
      CONFIG["NETWORK"]="host"
      echo -e "${YELLOW}检测到host版本，已自动切换网络模式为host${NC}"
    fi

    save_config

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
      cat "${CONFIG[BASE_DIR]}/mounts.conf" | awk '{print " " NR ". " $0}'
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
    mkdir -p "${CONFIG[BASE_DIR]}"
    echo "$mount_config" >> "${CONFIG[BASE_DIR]}/mounts.conf"
    echo -e "${GREEN}挂载配置已添加!${NC}"

    # 自动重建容器使挂载生效
    recreate_container_for_mounts
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
  local total_lines=$(wc -l < "${CONFIG[BASE_DIR]}/mounts.conf")

  if [[ "$mount_num" =~ ^[0-9]+$ ]] && [[ "$mount_num" -ge 1 ]] && [[ "$mount_num" -le "$total_lines" ]]; then
    # 创建临时文件
    local temp_file=$(mktemp)
    # 删除指定行
    sed "${mount_num}d" "${CONFIG[BASE_DIR]}/mounts.conf" > "$temp_file"
    mv "$temp_file" "${CONFIG[BASE_DIR]}/mounts.conf"
    echo -e "${GREEN}挂载配置已删除!${NC}"

    # 自动重建容器使挂载生效
    recreate_container_for_mounts
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

  # 显示端口映射（支持host和bridge模式）
  echo -e "\n${CYAN}============== 端口映射 ==============${NC}"
  if [[ "${CONFIG[NETWORK]}" == "host" ]]; then
    echo -e "${YELLOW}host模式使用主机网络，无独立端口映射${NC}"
    echo -e "管理端口: ${GREEN}4567${NC}"
    echo -e "Nginx端口: ${GREEN}5678${NC}"
    echo -e "httpd端口: ${GREEN}5233${NC}"
    echo -e "AList端口: ${GREEN}5234${NC}"
  else
    docker inspect --format \
      '{{range $p, $conf := .NetworkSettings.Ports}}{{$p}} -> {{(index $conf 0).HostPort}}{{"\n"}}{{end}}' \
      "$container_name" 2>/dev/null || echo -e "${RED}无端口映射信息${NC}"
  fi

  # 显示挂载信息（包括自定义挂载）
  echo -e "\n${CYAN}============== 挂载目录 ==============${NC}"
  docker inspect --format \
    '{{range $mount := .Mounts}}{{.Source}}:{{.Destination}}:{{.Mode}}'$'\n''{{end}}' \
    "$container_name" 2>/dev/null | \
  awk -F: '{
    max_source = (length($1) > max_source) ? length($1) : max_source;
    max_dest = (length($2) > max_dest) ? length($2) : max_dest;
    mounts[NR] = $0
  } END {
    for(i=1; i<NR; i++) {
      split(mounts[i], arr, ":");
      printf "  %-*s -> %-*s (%s)\n", max_source, arr[1], max_dest, arr[2], arr[3]
    }
  }'

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

  # 检查AList服务状态
  if [[ "$status" == "running" ]]; then
    echo -e "\n${CYAN}============ AList服务状态 ============${NC}"
    check_alist_status
  fi

  read -n 1 -s -r -p "按任意键继续..."
}

# 显示网络模式菜单
show_network_menu() {
  clear
  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          网络模式设置          ${NC}"
  echo -e "${CYAN}=============================================${NC}"
  echo -e " 当前网络模式: ${GREEN}${CONFIG[NETWORK]}${NC}"
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
      ;;
    2)
      CONFIG["NETWORK"]="host"
      save_config
      echo -e "${GREEN}已设置为host模式${NC}"
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
    local container_name=$(get_container_name)
    if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
      echo -e "${YELLOW}注意: 网络模式变更将在下次启动容器时生效${NC}"
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
    echo -e " 0. 返回主菜单"
    echo -e "${CYAN}---------------------------------------------${NC}"
    read -p "选择要修改的配置 [0-9]: " config_choice

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
        need_recreate=true
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
        read -p "输入GitHub代理URL [${CONFIG[GITHUB_PROXY]}]: " url
        if [[ "$url" != "${CONFIG[GITHUB_PROXY]}" ]]; then
          CONFIG[GITHUB_PROXY]="$url"
          save_config
          need_recreate=true
        fi
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

  if [[ "${CONFIG[NETWORK]}" == "host" ]]; then
    check_url "管理应用" "http://${ip}:4567/"
    check_url "Nginx" "http://${ip}:5678/"
    check_url "httpd" "http://${ip}:5233/"
    check_url "AList" "http://${ip}:5234/"
  else
    check_url "管理应用" "http://${ip}:${CONFIG[PORT1]}/"
    check_url "入口服务" "http://${ip}:${CONFIG[PORT2]}/"
  fi
}

auto_repair() {
  local container_name
  container_name="$(get_container_name)"

  echo -e "${CYAN}正在执行自动修复...${NC}"

  if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
    local running
    running="$(docker inspect -f '{{.State.Running}}' "$container_name" 2>/dev/null || echo false)"
    if [[ "$running" != "true" ]]; then
      echo -e "${YELLOW}容器未运行，正在启动...${NC}"
      if ! docker start "$container_name" >/dev/null; then
        echo -e "${RED}容器启动失败，请查看日志：docker logs -f $container_name${NC}"
      fi
    else
      echo -e "${GREEN}容器正在运行${NC}"
    fi
  else
    echo -e "${YELLOW}容器不存在，请先安装${NC}"
  fi

  health_check
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
          docker logs -f "$container_name"
        else
          echo -e "${YELLOW}容器不存在${NC}"
          sleep 1
        fi
        ;;
      6)
        echo "卸载容器..."
        if [ "$status" != "not_exist" ]; then
          docker rm -f "$container_name"
          echo -e "${GREEN}容器已卸载${NC}"
        else
          echo -e "${YELLOW}容器不存在${NC}"
        fi
        sleep 1
        ;;
      7)
        show_version_menu
        ;;
      8)
        show_config_menu
        ;;
      9)
        if ! check_update; then
          sleep 3
        fi
        ;;
      h|H)
        health_check
        read -n 1 -s -r -p "按任意键继续..."
        ;;
      r|R)
        auto_repair
        read -n 1 -s -r -p "按任意键继续..."
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
