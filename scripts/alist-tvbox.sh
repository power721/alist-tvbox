#!/usr/bin/env bash
set -euo pipefail

## 全局配置
readonly CONFIG_FILE="${HOME}/.config/alist-tvbox/app.conf"
readonly DEFAULT_BASE_DIR="${INITIAL_BASE_DIR:-$PWD/alist-tvbox}"
declare -A CONFIG DEFAULT_CONFIG VERSIONS

## 颜色定义
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[0;33m'
readonly BLUE='\033[0;34m'
readonly CYAN='\033[0;36m'
readonly MAGENTA='\033[0;35m'
readonly NC='\033[0m'

## 版本定义
VERSIONS=(
  ["1"]="haroldli/alist-tvbox              - 纯净版"
  ["2"]="haroldli/alist-tvbox:native       - 纯净原生版（推荐）"
  ["3"]="haroldli/alist-tvbox:python       - 纯净版（Python运行环境）"
  ["4"]="haroldli/xiaoya-tvbox             - 小雅集成版"
  ["5"]="haroldli/xiaoya-tvbox:native      - 小雅原生版（推荐）"
  ["6"]="haroldli/xiaoya-tvbox:native-host - 小雅原生主机版"
  ["7"]="haroldli/xiaoya-tvbox:host        - 小雅主机模式版"
  ["8"]="haroldli/xiaoya-tvbox:python      - 小雅版（Python运行环境）"
  ["9"]="haroldli/xiaoya-tvbox:dev         - 开发测试版"
)

## 默认配置
DEFAULT_CONFIG=(
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

# 初始化配置
initialize_config() {
  local key
  for key in "${!DEFAULT_CONFIG[@]}"; do
    CONFIG["$key"]="${DEFAULT_CONFIG[$key]}"
  done
}

# 检查是否为NAS设备
is_nas() {
  # 检查群晖DSM
  [[ -f "/proc/sys/kernel/syno_hw_version" ]] && return 0

  # 检查其他NAS系统
  if [[ -f "/etc/os-release" ]]; then
    grep -q -E "Synology|QNAP|TrueNAS" /etc/os-release && return 0
  fi

  return 1
}

# 检查群晖NAS
is_synology_nas() {
  if uname -a | grep -iq "synology" || \
     ps aux | grep -q "[s]ynoservice" || \
     [[ -f "/etc.defaults/VERSION" ]]; then
    return 0
  fi
  return 1
}

# 检查NAS存储配置
is_nas_storage() {
  if lsblk -o FSTYPE | grep -q "btrfs" || \
     [[ $(df -T / | awk 'NR==2 {print $2}') == "btrfs" ]] || \
     [[ -f "/proc/mdstat" && $(grep -c "active raid" /proc/mdstat) -gt 0 ]]; then
    return 0
  fi
  return 1
}

# 检查NAS环境
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

# 检查运行环境
check_environment() {
  echo -e "${CYAN}正在检测运行环境...${NC}"

  # 检查Docker是否安装
  if ! command -v docker &>/dev/null; then
    echo -e "${RED}错误：Docker未安装！${NC}"
    exit 1
  fi

  # 检查Docker服务状态
  if ! docker info &>/dev/null; then
    echo -e "${RED}错误：无法连接 Docker 服务！${NC}"
    echo -e "${YELLOW}请确保："
    echo -e "1. Docker 已安装并运行"
    echo -e "2. 当前用户已加入 'docker' 组${NC}"
    exit 1
  fi
}

# 获取容器名称
get_container_name() {
  [[ "${CONFIG[IMAGE_NAME]}" == *"alist-tvbox"* ]] && echo "alist-tvbox" || echo "xiaoya-tvbox"
}

# 获取对立容器名称
get_opposite_container_name() {
  [[ "${CONFIG[IMAGE_NAME]}" == *"alist-tvbox"* ]] && echo "xiaoya-tvbox" || echo "alist-tvbox"
}

# 检查现有容器
check_existing_container() {
  local container_name=$(get_container_name)
  local opposite_name=$(get_opposite_container_name)

  if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
    echo -e "${GREEN}检测到已存在的容器: ${container_name}${NC}"
    return 0
  elif docker ps -a --format '{{.Names}}' | grep -q "^${opposite_name}\$"; then
    echo -e "${YELLOW}检测到对立容器: ${opposite_name}${NC}"
    return 1
  else
    echo -e "${YELLOW}未找到现有容器${NC}"
    return 2
  fi
}

# 从容器加载配置
get_container_config() {
  local container_name=$(get_container_name)
  local inspect_cmd="docker inspect --format"

  CONFIG["IMAGE_NAME"]=$($inspect_cmd '{{.Config.Image}}' "$container_name" 2>/dev/null)
  CONFIG["NETWORK"]=$($inspect_cmd '{{.HostConfig.NetworkMode}}' "$container_name" 2>/dev/null)

  if [[ "${CONFIG[NETWORK]}" != "host" ]]; then
    CONFIG["PORT1"]=$($inspect_cmd '{{(index (index .NetworkSettings.Ports "4567/tcp") 0).HostPort}}' "$container_name" 2>/dev/null || echo "4567")
    CONFIG["PORT2"]=$($inspect_cmd '{{(index (index .NetworkSettings.Ports "80/tcp") 0).HostPort}}' "$container_name" 2>/dev/null || echo "5344")
  fi

  CONFIG["RESTART"]=$($inspect_cmd '{{.HostConfig.RestartPolicy.Name}}' "$container_name" 2>/dev/null || echo "always")

  local mount_path=$($inspect_cmd '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}' "$container_name" 2>/dev/null)
  [[ -n "$mount_path" ]] && CONFIG["BASE_DIR"]="$mount_path"

  echo -e "${CYAN}已从现有容器加载配置${NC}"
}

# 加载配置文件
load_config() {
  initialize_config

  if [[ -f "$CONFIG_FILE" ]]; then
    while IFS='=' read -r key value; do
      [[ -n "$key" ]] && CONFIG["$key"]="$value"
    done < "$CONFIG_FILE"
  else
    if check_nas_environment; then
      CONFIG["BASE_DIR"]="/volume1/docker/alist-tvbox"
      CONFIG["NETWORK"]="host"
    fi

    echo -e "${CYAN}首次运行，正在检测现有容器...${NC}"
    if check_existing_container; then
      get_container_config
    fi

    mkdir -p "$(dirname "$CONFIG_FILE")"
    save_config

    [[ ! -d "${CONFIG[BASE_DIR]}" ]] && mkdir -p "${CONFIG[BASE_DIR]}"
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

# 检查容器状态
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
  echo -e "${CYAN}正在检查镜像更新...${NC}"

  local current_id=$(docker images --quiet "$image")
  echo -e "${CYAN}正在拉取镜像：${image}${NC}"

  if ! docker pull "$image" >/dev/null; then
    echo -e "${RED}镜像拉取失败!${NC}"
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
  local network_args="" port_args="" volume_args=""
  local aList_port=80

  [[ "${CONFIG[GITHUB_PROXY]}" ]] && echo "${CONFIG[GITHUB_PROXY]}" > "${CONFIG[BASE_DIR]}/github_proxy.txt"

  if [[ "$image" == *"alist-tvbox"* ]]; then
    aList_port=5244
    volume_args="-v ${CONFIG[BASE_DIR]}/alist:/opt/alist/data"
  fi

  # 添加/www挂载选项
  if [[ "${CONFIG[MOUNT_WWW]}" == "true" ]]; then
    volume_args+=" -v ${CONFIG[BASE_DIR]}/www:/www"
    mkdir -p "${CONFIG[BASE_DIR]}/www"
  fi

  # 添加自定义挂载
  if [[ -f "${CONFIG[BASE_DIR]}/mounts.conf" ]]; then
    while IFS= read -r line; do
      local host_dir=${line%%:*}
      [[ ! -e "$host_dir" ]] && mkdir -p "$host_dir"
      volume_args+=" -v $line"
    done < "${CONFIG[BASE_DIR]}/mounts.conf"
  fi

  if [[ "${CONFIG[NETWORK]}" == "host" ]]; then
    network_args="--network host"
    echo -e "${YELLOW}使用host网络模式${NC}"
  else
    port_args="-p ${CONFIG[PORT1]}:4567 -p ${CONFIG[PORT2]}:${aList_port}"
  fi

  mkdir -p "${CONFIG[BASE_DIR]}"

  docker run -d \
    --name "$container_name" \
    $port_args \
    $volume_args \
    -e ALIST_PORT="${CONFIG[PORT2]}" \
    -e MEM_OPT="-Xmx512M" \
    -v "${CONFIG[BASE_DIR]}":/data \
    --restart="${CONFIG[RESTART]}" \
    $network_args \
    "$image"
}

# 显示访问信息
show_access_info() {
  local container_name=$(get_container_name)
  local ip=$(get_host_ip)

  echo -e "\n${CYAN}============== 访问信息 ==============${NC}"
  echo -e "容器名称: ${GREEN}${container_name}${NC}"
  echo -e "管理界面: ${GREEN}http://${ip:-localhost}:${CONFIG[PORT1]}/${NC}"
  echo -e "AList界面: ${GREEN}http://${ip:-localhost}:${CONFIG[PORT2]}/${NC}"
  echo -e "${CYAN}=======================================${NC}"
  echo -e "查看日志: ${YELLOW}docker logs -f $container_name${NC}"
}

# 获取主机IP
get_host_ip() {
  local ip
  if command -v ip &>/dev/null; then
    ip=$(ip route get 1 | awk '{print $(NF-2);exit}')
  elif command -v hostname &>/dev/null; then
    ip=$(hostname -I | awk '{print $1}')
  else
    ip="localhost"
  fi
  echo "$ip"
}

# 检查架构支持
check_architecture_support() {
  local arch=$(uname -m)
  case "$arch" in
    x86_64) return 0 ;;
    aarch64)
      [[ "${CONFIG[IMAGE_ID]}" =~ ^[256]$ ]] && {
        echo -e "${RED}错误: ARM64 不支持native版本${NC}"
        echo -e "请选择其他版本（如 1、3、4、7、8）"
        return 1
      }
      return 0 ;;
    armv*)
      echo -e "${RED}错误: 不支持 ARMv7 (32位) 架构${NC}"
      return 1 ;;
    *)
      echo -e "${RED}错误: 不支持的架构: $arch${NC}"
      return 1 ;;
  esac
}

# 安装/更新容器
install_container() {
  check_architecture_support || return 1

  if [[ "${CONFIG[IMAGE_NAME]}" == *"host"* ]]; then
    CONFIG["NETWORK"]="host"
    echo -e "${YELLOW}检测到host版本，已自动切换网络模式为host${NC}"
    save_config
  fi

  local container_name=$(get_container_name)
  remove_opposite_container

  [[ -d "${CONFIG[BASE_DIR]}" ]] || mkdir -p "${CONFIG[BASE_DIR]}"

  if check_image_update; then
    echo -e "${GREEN}正在更新容器...${NC}"
  else
    echo -e "${YELLOW}没有新版本可用，继续使用当前镜像${NC}"
  fi

  docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$" && {
    echo -e "${YELLOW}正在移除现有容器...${NC}"
    docker rm -f "$container_name" >/dev/null
  }

  start_container
  echo -e "${GREEN}操作成功完成!${NC}"
  show_access_info
  read -n 1 -s -r -p "按任意键继续..."
}

# 检查更新
check_update() {
  local auto_update=false
  [[ "$#" -ge 1 && "$1" == "-y" ]] && auto_update=true

  local image="${CONFIG[IMAGE_NAME]}"
  echo -e "${CYAN}正在检查镜像更新...${NC}"

  local current_id=$(docker images --quiet "$image")
  echo -e "${CYAN}正在拉取镜像: $image${NC}"

  if ! docker pull "$image" >/dev/null; then
    echo -e "${RED}镜像拉取失败!${NC}"
    return 1
  fi

  local new_id=$(docker images --quiet "$image")
  if [[ "$current_id" != "$new_id" ]]; then
    echo -e "${GREEN}检测到新版本镜像${NC}"
    if $auto_update; then
      local container_name=$(get_container_name)
      if docker ps --format '{{.Names}}' | grep -q "^${container_name}\$"; then
        echo -e "${YELLOW}正在重启容器...${NC}"
        docker restart "$container_name"
      else
        echo -e "${GREEN}正在启动容器...${NC}"
        docker start "$container_name"
      fi
      return 0
    else
      read -p "检测到新版本，是否立即更新容器？[Y/n] " yn
      case $yn in
        [Nn]*) ;;
        *)
          local container_name=$(get_container_name)
          if docker ps --format '{{.Names}}' | grep -q "^${container_name}\$"; then
            docker restart "$container_name"
          else
            docker start "$container_name"
          fi
          ;;
      esac
    fi
  else
    echo -e "${YELLOW}当前已是最新版本${NC}"
    return 1
  fi
}

# 显示版本选择菜单
show_version_menu() {
  while :; do
    clear
    echo -e "${CYAN}=============================================${NC}"
    echo -e "${GREEN}          请选择要使用的版本          ${NC}"
    echo -e "${CYAN}=============================================${NC}"

    local arch=$(uname -m)
    local current_version="${CONFIG[IMAGE_ID]}"

    for key in {1..9}; do
      [[ "$arch" == "aarch64" && "$key" =~ ^[256]$ ]] && continue
      if [[ "$key" == "$current_version" ]]; then
        echo -e "${GREEN} $key. ${VERSIONS[$key]}${NC} (当前使用)"
      else
        echo -e "${YELLOW} $key. ${VERSIONS[$key]}${NC}"
      fi
    done

    echo -e "${GREEN} 0. 返回主菜单${NC}"
    echo -e "${CYAN}---------------------------------------------${NC}"

    while :; do
      read -p "请输入版本编号 [0-9]: " version_choice
      [[ "$version_choice" =~ ^[0-9]$ ]] && break
      echo -e "${RED}无效输入! 请输入0-9的数字${NC}"
    done

    [[ "$version_choice" == "0" ]] && return

    local old_version="${CONFIG[IMAGE_NAME]}"
    local image="${VERSIONS[$version_choice]%% -*}"
    image=$(tr -d '[:space:]' <<< "$image" | sed "s/^['\"]//;s/['\"]\$//")

    CONFIG["IMAGE_ID"]="$version_choice"
    CONFIG["IMAGE_NAME"]="$image"

    [[ "$image" == *"host"* ]] && {
      CONFIG["NETWORK"]="host"
      echo -e "${YELLOW}检测到host版本，已自动切换网络模式为host${NC}"
    }

    save_config

    local container_name=$(get_container_name)
    local opposite_name=$(get_opposite_container_name)

    # 删除对立容器
    docker ps -a --format '{{.Names}}' | grep -q "^${opposite_name}\$" && {
      echo -e "${YELLOW}正在移除对立容器 ${opposite_name}...${NC}"
      docker rm -f "$opposite_name" >/dev/null
    }

    # 如果容器存在，则停止并删除
    docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$" && {
      echo -e "${YELLOW}正在停止并删除旧容器...${NC}"
      docker rm -f "$container_name" >/dev/null
    }

    # 启动新容器
    echo -e "${YELLOW}正在启动新版本容器...${NC}"
    start_container

    echo -e "${GREEN}版本已切换为: $image${NC}"
    show_access_info
    read -n 1 -s -r -p "按任意键继续..."
    return
  done
}

# 重置管理员密码
reset_admin_password() {
  local container_name=$(get_container_name)
  local cmd_file="${CONFIG[BASE_DIR]}/atv/cmd.sql"

  mkdir -p "$(dirname "$cmd_file")"
  echo "UPDATE users SET username='admin', password='\$2a\$10\$90MH0QCl098tffOA3ZBDwu0pm24xsVyJeQ41Tvj7N5bXspaqg8b2m' WHERE id=1;" > "$cmd_file"

  local status=$(check_container_status)
  if [[ "$status" == "running" ]]; then
    echo -e "${YELLOW}正在重启容器使密码重置生效...${NC}"
    docker restart "$container_name"
    echo -e "${GREEN}管理员密码已重置为默认密码!${NC}"
    echo -e "${YELLOW}请尽快登录管理界面修改密码!${NC}"
  else
    echo -e "${GREEN}管理员密码将在容器启动时重置为默认密码!${NC}"
    echo -e "${YELLOW}请启动容器后尽快登录管理界面修改密码!${NC}"
  fi
  sleep 3
}

# 管理自定义挂载目录
manage_custom_mounts() {
  while :; do
    clear
    echo -e "${CYAN}=============================================${NC}"
    echo -e "${GREEN}          自定义挂载目录管理          ${NC}"
    echo -e "${CYAN}=============================================${NC}"

    if [[ -f "${CONFIG[BASE_DIR]}/mounts.conf" ]]; then
      echo -e "${YELLOW}当前挂载配置:${NC}"
      awk '{print " " NR ". " $0}' "${CONFIG[BASE_DIR]}/mounts.conf"
    else
      echo -e "${YELLOW}暂无自定义挂载${NC}"
    fi

    echo -e "\n${GREEN} 1. 添加挂载目录"
    echo -e " 2. 删除挂载目录"
    echo -e " 0. 返回配置菜单${NC}"
    echo -e "${CYAN}---------------------------------------------${NC}"
    read -p "请选择操作 [0-2]: " mount_choice

    case $mount_choice in
      1) add_custom_mount ;;
      2) remove_custom_mount ;;
      0) break ;;
      *) echo -e "${RED}无效选择!${NC}"; sleep 1 ;;
    esac
  done
}

# 添加自定义挂载
add_custom_mount() {
  echo -e "${YELLOW}格式: 主机目录:容器目录[:权限]"
  echo -e "示例: /path/on/host:/path/in/container:ro${NC}"
  read -p "请输入挂载配置: " mount_config

  if [[ "$mount_config" =~ ^[^:]+:[^:]+(:ro|:rw)?$ ]]; then
    mkdir -p "${CONFIG[BASE_DIR]}"
    echo "$mount_config" >> "${CONFIG[BASE_DIR]}/mounts.conf"
    echo -e "${GREEN}挂载配置已添加!${NC}"
    recreate_container_for_mounts
  else
    echo -e "${RED}无效格式! 请使用 主机目录:容器目录[:权限] 格式${NC}"
  fi
  sleep 1
}

# 删除自定义挂载
remove_custom_mount() {
  [[ -f "${CONFIG[BASE_DIR]}/mounts.conf" ]] || {
    echo -e "${YELLOW}暂无自定义挂载配置${NC}"
    sleep 1
    return
  }

  read -p "请输入要删除的挂载编号: " mount_num
  local total_lines=$(wc -l < "${CONFIG[BASE_DIR]}/mounts.conf")

  if [[ "$mount_num" =~ ^[0-9]+$ ]] && (( mount_num >= 1 && mount_num <= total_lines )); then
    local temp_file=$(mktemp)
    sed "${mount_num}d" "${CONFIG[BASE_DIR]}/mounts.conf" > "$temp_file"
    mv "$temp_file" "${CONFIG[BASE_DIR]}/mounts.conf"
    echo -e "${GREEN}挂载配置已删除!${NC}"
    recreate_container_for_mounts
  else
    echo -e "${RED}无效编号!${NC}"
  fi
  sleep 1
}

# 重建容器使挂载生效
recreate_container_for_mounts() {
  local container_name=$(get_container_name)

  if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
    echo -e "${YELLOW}正在重建容器使挂载配置生效...${NC}"
    local was_running=$(docker inspect -f '{{.State.Running}}' "$container_name" 2>/dev/null)

    docker rm -f "$container_name" >/dev/null

    if [[ "$was_running" == "true" ]]; then
      start_container
      echo -e "${GREEN}容器已重建并启动!${NC}"
    else
      echo -e "${GREEN}容器已重建!${NC}"
    fi
  else
    echo -e "${YELLOW}容器不存在，挂载配置将在下次启动时生效${NC}"
  fi
}

# 重建容器使配置变更生效
recreate_container_for_changes() {
  local container_name=$(get_container_name)

  if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
    echo -e "${YELLOW}正在重建容器使配置变更生效...${NC}"
    local was_running=$(docker inspect -f '{{.State.Running}}' "$container_name" 2>/dev/null)

    docker rm -f "$container_name" >/dev/null

    if [[ "$was_running" == "true" ]]; then
      start_container
      echo -e "${GREEN}容器已重建并启动!${NC}"
    else
      echo -e "${GREEN}容器已重建!${NC}"
    fi
  else
    echo -e "${YELLOW}容器不存在，变更将在下次启动时生效${NC}"
  fi
  sleep 1
}

# 检查 AList 运行状态
check_alist_status() {
  local ip=$(get_host_ip)
  local port="${CONFIG[PORT1]}"
  local api_url="http://$ip:$port/api/alist/status"

  echo -e "${CYAN}正在检查 AList 状态...${NC}"

  if status_code=$(curl -s --connect-timeout 3 "$api_url"); then
    case "$status_code" in
      0) echo -e "AList 状态: ${RED}未启动${NC}" ;;
      1) echo -e "AList 状态: ${YELLOW}启动中...${NC}" ;;
      2) echo -e "AList 状态: ${GREEN}已启动${NC}" ;;
      *) echo -e "AList 状态: ${RED}未知状态码: $status_code${NC}" ;;
    esac
  else
    echo -e "AList 状态: ${RED}无法连接到管理应用${NC}"
  fi
}

# 检查容器状态
check_status() {
  local container_name=$(get_container_name)
  local status=$(check_container_status)

  echo -e "${CYAN}============== 容器基础信息 ==============${NC}"
  docker ps -a --filter "name=$container_name" --format \
    "table {{.Names}}\t{{.Status}}\t{{.Image}}"

  echo -e "\n${CYAN}============== 端口映射 ==============${NC}"
  if [[ "${CONFIG[NETWORK]}" == "host" ]]; then
    echo -e "${YELLOW}host模式使用主机网络，无独立端口映射${NC}"
    echo -e "管理端口: ${GREEN}4567${NC}"
    echo -e "AList端口: ${GREEN}5244${NC}"
  else
    docker inspect --format \
      '{{range $p, $conf := .NetworkSettings.Ports}}{{$p}} -> {{(index $conf 0).HostPort}}{{"\n"}}{{end}}' \
      "$container_name" 2>/dev/null || echo -e "${RED}无端口映射信息${NC}"
  fi

  echo -e "\n${CYAN}============== 挂载目录 ==============${NC}"
  docker inspect --format \
    '{{range $mount := .Mounts}}{{.Source}}:{{.Destination}}:{{.Mode}}'$'\n''{{end}}' \
    "$container_name" 2>/dev/null | \
  awk -F: '{
    max_source = (length($1) > max_source) ? length($1) : max_source;
    max_dest = (length($2) > max_dest) ? length($2) : max_dest;
    mounts[NR] = $0
  } END {
    for(i=1; i<=NR; i++) {
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
    1) CONFIG["RESTART"]="always" ;;
    2) CONFIG["RESTART"]="unless-stopped" ;;
    3) CONFIG["RESTART"]="no" ;;
    0) return ;;
  esac

  if [[ "$choice" != "0" ]]; then
    save_config
    docker update --restart="${CONFIG[RESTART]}" "$(get_container_name)" >/dev/null 2>&1
    sleep 1
  fi
}

# 显示配置管理菜单
show_config_menu() {
  while :; do
    clear
    echo -e "${CYAN}=============================================${NC}"
    echo -e "${GREEN}          当前配置管理          ${NC}"
    echo -e "${CYAN}=============================================${NC}"
    echo -e " 1. 数据目录: ${CONFIG[BASE_DIR]}"
    echo -e " 2. 管理端口: ${CONFIG[PORT1]}"
    echo -e " 3. AList端口: ${CONFIG[PORT2]}"
    echo -e " 4. 挂载/www目录: ${CONFIG[MOUNT_WWW]}"
    echo -e " 5. 自定义挂载目录"
    echo -e " 6. 网络模式设置"
    echo -e " 7. 重启策略设置"
    echo -e " 8. 重置管理员密码"
    echo -e " 9. GitHub代理设置"
    echo -e " 0. 返回主菜单"
    echo -e "${CYAN}---------------------------------------------${NC}"
    read -p "选择要修改的配置 [0-9]: " config_choice

    local need_recreate=false

    case $config_choice in
      1)
        read -p "输入新的数据目录 [${CONFIG[BASE_DIR]}]: " new_dir
        [[ -n "$new_dir" && "$new_dir" != "${CONFIG[BASE_DIR]}" ]] && {
          CONFIG[BASE_DIR]="$new_dir"
          save_config
          need_recreate=true
        }
        ;;
      2)
        read -p "输入新的管理端口 [${CONFIG[PORT1]}]: " new_port
        if [[ "$new_port" =~ ^[0-9]+$ ]]; then
          [[ -n "$new_port" && "$new_port" != "${CONFIG[PORT1]}" ]] && {
            CONFIG[PORT1]="$new_port"
            save_config
            need_recreate=true
          }
        else
          echo -e "${RED}端口号必须是数字!${NC}"
          sleep 1
        fi
        ;;
      3)
        read -p "输入新的AList端口 [${CONFIG[PORT2]}]: " new_port
        if [[ "$new_port" =~ ^[0-9]+$ ]]; then
          [[ -n "$new_port" && "$new_port" != "${CONFIG[PORT2]}" ]] && {
            CONFIG[PORT2]="$new_port"
            save_config
            need_recreate=true
          }
        else
          echo -e "${RED}端口号必须是数字!${NC}"
          sleep 1
        fi
        ;;
      4)
        [[ "${CONFIG[MOUNT_WWW]}" == "true" ]] && CONFIG["MOUNT_WWW"]="false" || CONFIG["MOUNT_WWW"]="true"
        save_config
        need_recreate=true
        ;;
      5)
        manage_custom_mounts
        continue
        ;;
      6)
        show_network_menu
        need_recreate=true
        continue
        ;;
      7)
        show_restart_menu
        continue
        ;;
      8)
        reset_admin_password
        continue
        ;;
      9)
        read -p "输入GitHub代理URL [${CONFIG[GITHUB_PROXY]}]: " url
        [[ "$url" != "${CONFIG[GITHUB_PROXY]}" ]] && {
          CONFIG[GITHUB_PROXY]="$url"
          save_config
          need_recreate=true
        }
        ;;
      0)
        break
        ;;
    esac

    $need_recreate && recreate_container_for_changes
  done
}

# 显示主菜单
show_menu() {
  clear
  local status=$(check_container_status)
  local container_name=$(get_container_name)

  echo -e "${CYAN}==============================================${NC}"
  echo -e "${GREEN}          AList TvBox 安装升级配置管理          ${NC}"
  echo -e "${CYAN}==============================================${NC}"
  echo -e "${YELLOW} 当前版本: ${CONFIG[IMAGE_NAME]}${NC}"
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
  echo -e "${CYAN}---------------------------------------------${NC}"
  echo -e "${GREEN} 1. 安装/更新${NC}"

  case "$status" in
    "running") echo -e "${GREEN} 2. 停止容器${NC}" ;;
    *) echo -e "${GREEN} 2. 启动容器${NC}" ;;
  esac

  echo -e "${GREEN} 3. 重启容器${NC}"
  echo -e "${GREEN} 4. 查看状态${NC}"
  echo -e "${GREEN} 5. 查看日志${NC}"
  echo -e "${GREEN} 6. 卸载容器${NC}"
  echo -e "${GREEN} 7. 选择版本${NC}"
  echo -e "${GREEN} 8. 配置管理${NC}"
  echo -e "${GREEN} 9. 检查更新${NC}"
  echo -e "${GREEN} 0. 退出${NC}"
  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "请输入选项 [0-9]: " choice
}

# 交互模式
interactive_mode() {
  check_environment
  load_config

  while :; do
    show_menu
    local container_name=$(get_container_name)
    local status=$(check_container_status)

    case $choice in
      1) install_container ;;
      2)
        case "$status" in
          "running")
            docker stop "$container_name"
            echo -e "${GREEN}容器已停止${NC}"
            ;;
          *)
            if docker start "$container_name" 2>/dev/null; then
              echo -e "${GREEN}容器已启动${NC}"
            else
              echo -e "${RED}启动失败，容器不存在${NC}"
              read -p "是否立即安装容器？[Y/n] " yn
              case $yn in
                [Nn]*) ;;
                *) install_container ;;
              esac
            fi
            ;;
        esac
        sleep 1
        ;;
      3)
        if [[ "$status" != "not_exist" ]]; then
          docker restart "$container_name"
          echo -e "${GREEN}容器已重启${NC}"
        else
          echo -e "${RED}容器不存在，请先安装${NC}"
        fi
        sleep 1
        ;;
      4) check_status ;;
      5)
        if [[ "$status" != "not_exist" ]]; then
          docker logs -f "$container_name"
        else
          echo -e "${YELLOW}容器不存在${NC}"
          sleep 1
        fi
        ;;
      6)
        if [[ "$status" != "not_exist" ]]; then
          docker rm -f "$container_name"
          echo -e "${GREEN}容器已卸载${NC}"
        else
          echo -e "${YELLOW}容器不存在${NC}"
        fi
        sleep 1
        ;;
      7) show_version_menu ;;
      8) show_config_menu ;;
      9) check_update || sleep 3 ;;
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

# 命令行模式
cli_mode() {
  check_environment
  load_config
  local container_name=$(get_container_name)

  case "$1" in
    install) install_container ;;
    start)
      docker start "$container_name" || {
        echo -e "${RED}启动失败，容器不存在${NC}"
        exit 1
      }
      ;;
    stop)
      docker stop "$container_name" || {
        echo -e "${RED}停止失败，容器不存在${NC}"
        exit 1
      }
      ;;
    restart)
      docker restart "$container_name" || {
        echo -e "${RED}重启失败，容器不存在${NC}"
        exit 1
      }
      ;;
    status) check_status ;;
    logs)
      docker logs -f "$container_name" || {
        echo -e "${RED}容器不存在${NC}"
        exit 1
      }
      ;;
    uninstall)
      docker rm -f "$container_name" || {
        echo -e "${RED}容器不存在${NC}"
        exit 1
      }
      ;;
    update)
      if [[ "$#" -ge 2 && "$2" == "-y" ]]; then
        check_update "-y"
      else
        check_update
      fi
      ;;
    menu) interactive_mode ;;
    *)
      echo -e "${RED}未知命令: $1${NC}"
      echo "可用命令: install, start, stop, restart, status, logs, uninstall, update, menu"
      exit 1
      ;;
  esac
}

# 主入口
if [[ $# -eq 0 ]]; then
  interactive_mode
else
  cli_mode "$@"
fi
