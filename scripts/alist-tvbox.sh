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
  ["1"]="haroldli/alist-tvbox - 纯净版"
  ["2"]="haroldli/alist-tvbox-native - 纯净原生版（推荐）"
  ["3"]="haroldli/alist-tvbox-tg - 纯净TG版"
  ["4"]="haroldli/xiaoya-tvbox - 小雅集成版"
  ["5"]="haroldli/xiaoya-tvbox-native - 小雅原生版（推荐）"
  ["6"]="haroldli/xiaoya-tvbox-native-host - 小雅原生主机版"
  ["7"]="haroldli/xiaoya-tvbox-host - 小雅主机模式版"
  ["8"]="haroldli/xiaoya-tvbox-tg - 小雅TG版"
)

# 默认配置
CONFIG_FILE="/$HOME/.config/alist-tvbox/app.conf"

# 初始化基础目录
INITIAL_BASE_DIR="/etc/xiaoya"
if [[ -d "$INITIAL_BASE_DIR" ]]; then
    DEFAULT_BASE_DIR="$INITIAL_BASE_DIR"
else
    DEFAULT_BASE_DIR="$PWD/alist-tvbox"
fi

declare -A DEFAULT_CONFIG=(
  ["MODE"]="docker"
  ["IMAGE_ID"]="5"
  ["IMAGE_NAME"]="haroldli/xiaoya-tvbox-native"
  ["BASE_DIR"]="$DEFAULT_BASE_DIR"
  ["PORT1"]="4567"
  ["PORT2"]="5344"
  ["NETWORK"]="bridge"
  ["RESTART"]="always"
  ["MOUNT_WWW"]="false"
)

# 初始化配置字典
declare -A CONFIG
for key in "${!DEFAULT_CONFIG[@]}"; do
  CONFIG["$key"]="${DEFAULT_CONFIG[$key]}"
done

# 检测运行环境
check_environment() {
  echo -e "${CYAN}正在检测运行环境...${NC}"

  if ! command -v docker &>/dev/null; then
    echo -e "${RED}错误：Docker未安装！${NC}"
    exit 1
  fi

  if ! docker info &>/dev/null; then
    echo -e "${RED}错误：Docker服务未运行！${NC}"
    exit 1
  fi
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
    mkdir -p "$(dirname "$CONFIG_FILE")"
    save_config

    # 确保基础目录存在
    if [[ ! -d "${CONFIG[BASE_DIR]}" ]]; then
      mkdir -p "${CONFIG[BASE_DIR]}"
      echo -e "${YELLOW}创建基础目录: ${CONFIG[BASE_DIR]}${NC}"
    fi
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

# 获取容器名称
get_container_name() {
  case "${CONFIG[IMAGE_ID]}" in
    1|2|3) echo "alist-tvbox";;
    *) echo "xiaoya-tvbox";;
  esac
}

# 获取对立容器名称
get_opposite_container_name() {
  case "${CONFIG[IMAGE_ID]}" in
    1|2|3) echo "xiaoya-tvbox";;
    *) echo "alist-tvbox";;
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
  echo -e "${CYAN}正在检查镜像更新...${NC}"

  local current_id=$(docker images --quiet "$image")
  docker pull "$image" >/dev/null
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
  local network_args=""
  local port_args=""
  local volume_args=""

  # 为alist-tvbox的三个版本添加特殊挂载
  if [[ "${CONFIG[IMAGE_ID]}" =~ ^[123]$ ]]; then
    volume_args="-v ${CONFIG[BASE_DIR]}/alist:/opt/alist/data"
  fi

  # 添加/www挂载选项
  if [[ "${CONFIG[MOUNT_WWW]}" == "true" ]]; then
    volume_args="$volume_args -v ${CONFIG[BASE_DIR]}/www:/www"
    mkdir -p "${CONFIG[BASE_DIR]}/www"
  fi

  # 添加自定义挂载
  if [[ -f "${CONFIG[BASE_DIR]}/mounts.conf" ]]; then
    while IFS= read -r line; do
      # 检查主机目录是否存在，不存在则创建
      local host_dir=$(echo "$line" | cut -d':' -f1)
      if [[ ! -e "$host_dir" ]]; then
        mkdir -p "$host_dir"
        echo -e "${YELLOW}已创建主机目录: $host_dir${NC}"
      fi
      volume_args="$volume_args -v $line"
    done < "${CONFIG[BASE_DIR]}/mounts.conf"
  fi

  # 只有版本6和7可以使用host模式
  if [[ "${CONFIG[NETWORK]}" == "host" && ("${CONFIG[IMAGE_ID]}" == "6" || "${CONFIG[IMAGE_ID]}" == "7") ]]; then
    network_args="--network host"
    echo -e "${YELLOW}使用host网络模式${NC}"
  else
    # 如果不是版本6或7，强制使用bridge模式
    if [[ "${CONFIG[NETWORK]}" == "host" ]]; then
      CONFIG["NETWORK"]="bridge"
      save_config
      echo -e "${YELLOW}当前版本不支持host模式，已自动切换为bridge模式${NC}"
    fi
    port_args="-p ${CONFIG[PORT1]}:4567 -p ${CONFIG[PORT2]}:80"
  fi

  # 确保数据目录存在
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
  local ip=""
  if [[ "${CONFIG[NETWORK]}" != "host" ]]; then
    ip=$(ip a | grep -F '192.168.' | awk '{print $2}' | awk -F/ '{print $1}' | head -1)
    [[ -z "$ip" ]] && ip=$(ip a | grep -F '10.' | awk '{print $2}' | awk -F/ '{print $1}' | grep -E '\b10.' | head -1)
  fi

  echo -e "\n${CYAN}============== 访问信息 ==============${NC}"
  echo -e "容器名称: ${GREEN}${container_name}${NC}"
  echo -e "管理界面: ${GREEN}http://${ip:-localhost}:${CONFIG[PORT1]}/${NC}"
  echo -e "AList界面: ${GREEN}http://${ip:-localhost}:${CONFIG[PORT2]}/${NC}"
  echo -e "${CYAN}=======================================${NC}"
  echo -e "查看日志: ${YELLOW}docker logs -f $container_name${NC}"
}

# 显示交互式菜单
show_menu() {
  clear
  local status=$(check_container_status)
  local container_name=$(get_container_name)

  echo -e "${CYAN}==============================================${NC}"
  echo -e "${GREEN}          AList TVBox 安装升级配置管理          ${NC}"
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
  echo -e "${GREEN} 1. 安装/更新容器${NC}"

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
  echo -e "${GREEN} 4. 查看容器状态${NC}"
  echo -e "${GREEN} 5. 查看容器日志${NC}"
  echo -e "${GREEN} 6. 卸载容器${NC}"
  echo -e "${GREEN} 7. 选择版本${NC}"
  echo -e "${GREEN} 8. 配置管理${NC}"
  echo -e "${GREEN} 9. 检查更新${NC}"
  echo -e "${GREEN} 0. 退出${NC}"
  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "请输入选项 [0-9]: " choice
}

# 安装/更新容器
install_container() {
  local container_name=$(get_container_name)
  remove_opposite_container

  # 检查基础目录是否存在
  if [[ ! -d "${CONFIG[BASE_DIR]}" ]]; then
    echo -e "${YELLOW}基础目录不存在，正在创建: ${CONFIG[BASE_DIR]}${NC}"
    mkdir -p "${CONFIG[BASE_DIR]}"
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
  show_access_info
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
  echo -e "${CYAN}正在检查镜像更新...${NC}"

  local current_id=$(docker images --quiet "$image")
  docker pull "$image" >/dev/null
  local new_id=$(docker images --quiet "$image")

  if [[ "$current_id" != "$new_id" ]]; then
    echo -e "${GREEN}检测到新版本镜像${NC}"
    if [[ "$auto_update" == true ]]; then
      local container_name=$(get_container_name)
      if docker ps --format '{{.Names}}' | grep -q "^${container_name}\$"; then
        echo -e "${YELLOW}正在重启容器...${NC}"
        docker restart "$container_name"
      else
        echo -e "${GREEN}正在启动容器...${NC}"
        docker restart "$container_name"
      fi
      return 0
    else
      read -p "检测到新版本，是否立即更新容器？[Y/n] " yn
      case $yn in
        [Nn]* ) ;;
        * )
          local container_name=$(get_container_name)
          if docker ps --format '{{.Names}}' | grep -q "^${container_name}\$"; then
            echo -e "${YELLOW}正在重启容器...${NC}"
            docker restart "$container_name"
          else
            echo -e "${GREEN}正在启动容器...${NC}"
            docker restart "$container_name"
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
  while true; do
    clear
    echo -e "${CYAN}=============================================${NC}"
    echo -e "${GREEN}          请选择要使用的版本          ${NC}"
    echo -e "${CYAN}=============================================${NC}"
    for key in {1..8}; do
      echo -e "${YELLOW} $key. ${VERSIONS[$key]}${NC}"
    done
    echo -e "${GREEN} 0. 返回主菜单${NC}"
    echo -e "${CYAN}---------------------------------------------${NC}"

    while true; do
      read -p "请输入版本编号 [0-8]: " version_choice
      # 验证输入是否为0-8的数字
      if [[ "$version_choice" =~ ^[0-8]$ ]]; then
        break
      else
        echo -e "${RED}无效输入! 请输入0-8的数字${NC}"
      fi
    done

    # 如果选择0，返回主菜单
    if [[ "$version_choice" == "0" ]]; then
      return
    fi

    local old_version="${CONFIG[IMAGE_NAME]}"
    CONFIG["IMAGE_ID"]="$version_choice"
    CONFIG["IMAGE_NAME"]="${VERSIONS[$version_choice]%% -*}"
    save_config

    # 如果容器存在且版本变化，则重启容器
    local container_name=$(get_container_name)
    if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
      if [[ "$old_version" != "${CONFIG[IMAGE_NAME]}" ]]; then
        echo -e "${YELLOW}版本已变更，正在重启容器...${NC}"
        docker restart "$container_name"
      fi
    fi

    echo -e "${GREEN}版本已切换为: ${VERSIONS[$version_choice]}${NC}"
    sleep 2
    return
  done
}

# 添加重置密码函数
reset_admin_password() {
  local container_name=$(get_container_name)
  local cmd_file="${CONFIG[BASE_DIR]}/atv/cmd.sql"

  # 确保目录存在
  mkdir -p "$(dirname "$cmd_file")"

  # 创建密码重置命令文件
  echo "UPDATE users SET username='admin', password='\$2a\$10\$90MH0QCl098tffOA3ZBDwu0pm24xsVyJeQ41Tvj7N5bXspaqg8b2m' WHERE id=1;" > "$cmd_file"

  # 检查容器状态
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
  local container_name=$(get_container_name)

  if docker ps -a --format '{{.Names}}' | grep -q "^${container_name}\$"; then
    echo -e "${YELLOW}正在重建容器使挂载配置生效...${NC}"
    local was_running=$(docker inspect -f '{{.State.Running}}' "$container_name" 2>/dev/null)

    # 停止并删除现有容器
    docker rm -f "$container_name" >/dev/null

    # 重新创建容器
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

# 显示网络模式菜单
show_network_menu() {
  clear
  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          网络模式设置          ${NC}"
  echo -e "${CYAN}=============================================${NC}"
  echo -e " 当前网络模式: ${CONFIG[NETWORK]}"
  echo -e " 1. bridge模式 (默认)"

  # 只有版本6和7显示host模式选项
  if [[ "${CONFIG[IMAGE_ID]}" == "6" || "${CONFIG[IMAGE_ID]}" == "7" ]]; then
    echo -e " 2. host模式"
    echo -e " 0. 返回"
    max_choice=2
  else
    echo -e " 0. 返回"
    max_choice=1
  fi

  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "请选择网络模式 [0-$max_choice]: " choice

  case $choice in
    1)
      CONFIG["NETWORK"]="bridge"
      save_config
      echo -e "${GREEN}已设置为bridge模式${NC}"
      ;;
    2)
      if [[ "$max_choice" == "2" ]]; then
        CONFIG["NETWORK"]="host"
        save_config
        echo -e "${GREEN}已设置为host模式${NC}"
      fi
      ;;
    0)
      # 只有版本6和7会进入这个分支
      return
      ;;
  esac

  if [[ "$choice" != "0" ]]; then
    sleep 1
  fi
}

# 显示重启策略菜单
show_restart_menu() {
  clear
  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          重启策略设置          ${NC}"
  echo -e "${CYAN}=============================================${NC}"
  echo -e " 当前重启策略: ${CONFIG[RESTART]}"
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
    echo -e " 6. 网络模式设置"
    echo -e " 7. 重启策略设置"
    echo -e " 8. 重置管理员密码"
    echo -e " 0. 返回主菜单"
    echo -e "${CYAN}---------------------------------------------${NC}"
    read -p "选择要修改的配置 [0-8]: " config_choice

    case $config_choice in
      1)
        read -p "输入新的数据目录 [${CONFIG[BASE_DIR]}]: " new_dir
        [[ -n "$new_dir" ]] && CONFIG[BASE_DIR]="$new_dir"
        save_config
        ;;
      2)
        read -p "输入新的管理端口 [${CONFIG[PORT1]}]: " new_port
        [[ -n "$new_port" ]] && CONFIG[PORT1]="$new_port"
        save_config
        ;;
      3)
        read -p "输入新的AList端口 [${CONFIG[PORT2]}]: " new_port
        [[ -n "$new_port" ]] && CONFIG[PORT2]="$new_port"
        save_config
        ;;
      4)
        if [[ "${CONFIG[MOUNT_WWW]}" == "true" ]]; then
          CONFIG["MOUNT_WWW"]="false"
        else
          CONFIG["MOUNT_WWW"]="true"
        fi
        save_config
        if [[ "${CONFIG[MOUNT_WWW]}" == "true" ]]; then
          ACTION=""
        else
          ACTION="取消"
        fi
        echo -e "${GREEN}已${ACTION}挂载/www目录${NC}"
        sleep 1
        ;;
      5)
        manage_custom_mounts
        ;;
      6)
        show_network_menu
        continue
        ;;
      7)
        show_restart_menu
        continue
        ;;
      8)
        reset_admin_password
        ;;
      0)
        break
        ;;
    esac

    echo -e "${GREEN}配置已更新!${NC}"
    sleep 1
  done
}

# 主循环
interactive_mode() {
  check_environment
  load_config

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
          docker restart "$container_name"
          echo -e "${GREEN}容器已重启${NC}"
        else
          echo -e "${RED}容器不存在，请先安装${NC}"
        fi
        sleep 1
        ;;
      4)
        echo -e "${CYAN}容器状态:${NC}"
        docker ps -a --filter "name=$container_name"
        echo -e "\n${CYAN}资源使用:${NC}"
        docker stats --no-stream "$container_name" 2>/dev/null || echo -e "${YELLOW}容器未运行${NC}"
        read -n 1 -s -r -p "按任意键继续..."
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
        check_update
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

# 命令行模式处理
cli_mode() {
  check_environment
  load_config
  local container_name=$(get_container_name)

  case "$1" in
    install)
      install_container
      ;;
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
    status)
      docker ps -a --filter "name=$container_name"
      ;;
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
    menu)
      interactive_mode
      ;;
    *)
      echo -e "${RED}未知命令: $1${NC}"
      echo "可用命令: install, start, stop, restart, status, logs, uninstall, update, menu"
      exit 1
      ;;
  esac
}

# 判断运行模式
if [ $# -eq 0 ]; then
  interactive_mode
else
  cli_mode "$@"
fi