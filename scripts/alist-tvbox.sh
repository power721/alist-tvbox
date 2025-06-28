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
  ["2"]="haroldli/alist-tvbox-native - 纯净原生版"
  ["3"]="haroldli/alist-tvbox-tg - 纯净TG版"
  ["4"]="haroldli/xiaoya-tvbox - 小雅集成版"
  ["5"]="haroldli/xiaoya-tvbox-native - 小雅原生版"
  ["6"]="haroldli/xiaoya-tvbox-native-host - 小雅原生主机版"
  ["7"]="haroldli/xiaoya-tvbox-hostmode - 小雅主机模式版"
  ["8"]="haroldli/xiaoya-tvbox-tg - 小雅TG版"
)

# 默认配置
CONFIG_FILE="/etc/xiaoya/xiaoya.conf"
declare -A DEFAULT_CONFIG=(
  ["MODE"]="docker"
  ["VERSION_ID"]="2"
  ["IMAGE_NAME"]="haroldli/xiaoya-tvbox:native"
  ["BASE_DIR"]="/etc/xiaoya"
  ["PORT1"]="4567"
  ["PORT2"]="5344"
  ["NETWORK"]="bridge"
  ["RESTART"]="always"
)

# 初始化配置字典
declare -A CONFIG
for key in "${!DEFAULT_CONFIG[@]}"; do
  CONFIG["$key"]="${DEFAULT_CONFIG[$key]}"
done

# 精确检测端口占用情况（兼容Docker容器映射）
check_port() {
  local port=$1
  local type=$2
  local container_name=$(get_container_name)

  # 检查端口是否被监听
  if ! ss -tuln | grep -q "LISTEN.*:${port}\b"; then
    return 0
  fi

  # 获取占用端口的详细信息
  local port_info=$(sudo lsof -i :${port} -sTCP:LISTEN -Fc 2>/dev/null | head -1)
  port_info=${port_info#c}

  # 如果是Docker相关进程
  if [[ "$port_info" == "docker-proxy" ]] ||
     [[ "$port_info" == "docker-pr" ]] ||
     [[ "$port_info" == "com.docker"* ]] ||
     [[ "$port_info" == "containerd" ]]; then

    # 检查是否是当前容器映射的端口
    if docker ps --format '{{.Names}}' | grep -q "^${container_name}\$"; then
      local container_ports=$(docker port "$container_name" | grep ":${port}\$")
      if [[ -n "$container_ports" ]]; then
        echo -e "${YELLOW}提示：${type}端口 ${port} 被当前容器映射${NC}"
        return 0
      fi
    fi

    echo -e "${YELLOW}提示：${type}端口 ${port} 被Docker容器映射${NC}"
    return 0
  fi

  # 如果是其他进程占用
  echo -e "${RED}错误：${type}端口 ${port} 已被其他程序占用！${NC}"
  if [[ -n "$port_info" ]]; then
    echo -e "占用进程：$port_info"
  else
    echo -e "无法获取进程名，可能是系统进程"
  fi
  echo -e "使用以下命令查看详细信息："
  echo -e "  sudo lsof -i :${port} -sTCP:LISTEN"
  echo -e "或："
  echo -e "  sudo netstat -tulnp | grep ':${port}\b'"
  return 1
}

# 检测运行环境
check_environment() {
  echo -e "${CYAN}正在检测运行环境...${NC}"

  # 检查Docker是否安装
  if ! command -v docker &>/dev/null; then
    echo -e "${RED}错误：Docker未安装！${NC}"
    echo -e "请先安装Docker："
    echo -e "官方安装文档：https://docs.docker.com/engine/install/"
    exit 1
  fi

  # 检查Docker服务是否运行
  if ! docker info &>/dev/null; then
    echo -e "${YELLOW}Docker服务未运行，尝试启动...${NC}"

    if command -v sudo &>/dev/null && sudo -v &>/dev/null; then
      if sudo systemctl start docker; then
        echo -e "${GREEN}Docker服务已启动${NC}"
      else
        echo -e "${RED}无法启动Docker服务！${NC}"
        echo -e "请手动启动Docker服务后重试："
        echo -e "  sudo systemctl start docker"
        exit 1
      fi
    else
      echo -e "${RED}需要sudo权限来启动Docker服务！${NC}"
      echo -e "请使用以下命令启动Docker后重试："
      echo -e "  sudo systemctl start docker"
      exit 1
    fi
  fi

  # 检查当前用户是否在docker组
  if ! groups | grep -q '\bdocker\b'; then
    echo -e "${YELLOW}警告：当前用户不在docker组中${NC}"
    echo -e "这可能导致需要sudo权限才能运行docker命令"

    if command -v sudo &>/dev/null && sudo -v &>/dev/null; then
      read -p "是否要将当前用户添加到docker组？[Y/n] " yn
      case $yn in
        [Nn]* ) ;;
        * )
          if sudo usermod -aG docker $USER; then
            echo -e "${GREEN}已添加用户到docker组，请重新登录生效${NC}"
            echo -e "${YELLOW}注意：您需要注销并重新登录后才能生效${NC}"
            exit 0
          else
            echo -e "${RED}添加用户到docker组失败！${NC}"
          fi
          ;;
      esac
    fi
  fi

  echo -e "${GREEN}环境检测通过${NC}"
}

# 初始配置向导
init_config() {
  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          小雅TVBox初始配置向导          ${NC}"
  echo -e "${CYAN}=============================================${NC}"

  # 选择版本
  echo -e "${YELLOW}请选择要安装的版本:${NC}"
  for key in "${!VERSIONS[@]}"; do
    echo -e " ${key}. ${VERSIONS[$key]}"
  done
  while true; do
    read -p "请输入版本编号 [1-8]: " version_choice
    if [[ -n "${VERSIONS[$version_choice]}" ]]; then
      CONFIG["VERSION_ID"]="$version_choice"
      CONFIG["IMAGE_NAME"]="${VERSIONS[$version_choice]%% -*}"
      break
    else
      echo -e "${RED}无效选择，请重新输入!${NC}"
    fi
  done

  # 设置数据目录
  read -p "输入数据存储目录 [默认:/etc/xiaoya]: " base_dir
  CONFIG["BASE_DIR"]="${base_dir:-/etc/xiaoya}"

  # 设置端口并检查占用
  while true; do
    read -p "输入管理端口(默认4567): " port1
    CONFIG["PORT1"]="${port1:-4567}"
    if check_port "${CONFIG[PORT1]}" "管理"; then
      break
    fi
  done

  while true; do
    read -p "输入AList端口(默认5344): " port2
    CONFIG["PORT2"]="${port2:-5344}"
    if check_port "${CONFIG[PORT2]}" "AList"; then
      break
    fi
  done

  # 网络模式
  read -p "输入网络模式(bridge/host) [默认:bridge]: " network
  CONFIG["NETWORK"]="${network:-bridge}"

  # 重启策略
  read -p "输入重启策略(always/unless-stopped/no) [默认:always]: " restart
  CONFIG["RESTART"]="${restart:-always}"

  # 保存配置
  mkdir -p "$(dirname "$CONFIG_FILE")"
  save_config
  echo -e "${GREEN}初始配置已完成!${NC}"
  echo -e "${YELLOW}配置文件保存在: ${CONFIG_FILE}${NC}"
  sleep 2
}

# 加载配置
load_config() {
  # 如果配置文件不存在，运行初始配置向导
  if [[ ! -f "$CONFIG_FILE" ]]; then
    init_config
    return
  fi

  # 加载现有配置
  while IFS='=' read -r key value; do
    if [[ -n "$key" && -n "${DEFAULT_CONFIG[$key]+x}" ]]; then
      CONFIG["$key"]="$value"
    fi
  done < "$CONFIG_FILE"

  # 确保所有配置项都有值
  for key in "${!DEFAULT_CONFIG[@]}"; do
    if [[ -z "${CONFIG[$key]+x}" ]]; then
      CONFIG["$key"]="${DEFAULT_CONFIG[$key]}"
    fi
  done
}

# 保存配置
save_config() {
  {
    for key in "${!DEFAULT_CONFIG[@]}"; do
      echo "$key=${CONFIG[$key]}"
    done
  } > "$CONFIG_FILE"
  chmod 600 "$CONFIG_FILE"
}

# 根据版本获取容器名称
get_container_name() {
  case "${CONFIG[VERSION_ID]}" in
    1|2|3)
      echo "alist-tvbox"  # 纯净版
      ;;
    *)
      echo "xiaoya-tvbox"  # 小雅版
      ;;
  esac
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
  if [[ "${CONFIG[NETWORK]}" == "host" ]]; then
    echo -e "管理界面: ${GREEN}http://localhost:${CONFIG[PORT1]}/${NC}"
    echo -e "AList界面: ${GREEN}http://localhost:${CONFIG[PORT2]}/${NC}"
  else
    echo -e "管理界面: ${GREEN}http://${ip:-localhost}:${CONFIG[PORT1]}/${NC}"
    echo -e "AList界面: ${GREEN}http://${ip:-localhost}:${CONFIG[PORT2]}/${NC}"
  fi
  echo -e "${CYAN}=======================================${NC}"
  echo -e "查看日志: ${YELLOW}docker logs -f $container_name${NC}"
}

# 安装/更新容器
install_container() {
  # 检查端口占用
  if [[ "${CONFIG[NETWORK]}" != "host" ]]; then
    check_port "${CONFIG[PORT1]}" "管理" || exit 1
    check_port "${CONFIG[PORT2]}" "AList" || exit 1
  fi

  local image="${CONFIG[IMAGE_NAME]}"
  local container_name=$(get_container_name)
  local network_args=""
  local port_args=""

  # 设置网络参数
  if [[ "${CONFIG[NETWORK]}" == "host" ]]; then
    network_args="--network host"
  else
    port_args="-p ${CONFIG[PORT1]}:4567 -p ${CONFIG[PORT2]}:80"
  fi

  # 停止并移除旧容器
  if docker inspect "$container_name" &>/dev/null; then
    echo -e "${YELLOW}发现已存在容器，停止并移除...${NC}"
    docker stop "$container_name" >/dev/null
    docker rm "$container_name" >/dev/null
  fi

  # 创建数据目录
  mkdir -p "${CONFIG[BASE_DIR]}"

  # 固定内存参数为512MB
  local mem_args="-Xmx512M"

  # 特殊版本处理
  local extra_args=""
  case "${CONFIG[VERSION_ID]}" in
    4|5)
      extra_args+=" --network host"
      ;;
  esac

  # 拉取镜像
  echo -e "${BLUE}正在拉取镜像: ${image}${NC}"
  docker pull "$image"

  # 运行容器
  echo -e "${GREEN}启动容器...${NC}"
  docker run -d \
    --name "$container_name" \
    $port_args \
    -e ALIST_PORT="${CONFIG[PORT2]}" \
    -e MEM_OPT="$mem_args" \
    -v "${CONFIG[BASE_DIR]}":/data \
    --restart="${CONFIG[RESTART]}" \
    $network_args \
    $extra_args \
    "$image"

  echo -e "${GREEN}操作成功完成!${NC}"
  show_access_info
  read -n 1 -s -r -p "按任意键继续..."
}

# 显示交互式菜单
show_menu() {
  clear
  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          小雅TVBox交互式管理系统          ${NC}"
  echo -e "${CYAN}=============================================${NC}"
  echo -e "${YELLOW} 当前版本: ${CONFIG[IMAGE_NAME]}${NC}"
  echo -e "${CYAN}---------------------------------------------${NC}"
  echo -e "${GREEN} 1. 安装/更新容器${NC}"
  echo -e "${GREEN} 2. 启动容器${NC}"
  echo -e "${GREEN} 3. 停止容器${NC}"
  echo -e "${GREEN} 4. 重启容器${NC}"
  echo -e "${GREEN} 5. 查看容器状态${NC}"
  echo -e "${GREEN} 6. 查看容器日志${NC}"
  echo -e "${GREEN} 7. 卸载容器${NC}"
  echo -e "${GREEN} 8. 选择版本${NC}"
  echo -e "${GREEN} 9. 配置管理${NC}"
  echo -e "${GREEN} 0. 退出${NC}"
  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "请输入选项 [0-9]: " choice
}

# 显示版本选择菜单
show_version_menu() {
  clear
  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          请选择要使用的版本          ${NC}"
  echo -e "${CYAN}=============================================${NC}"
  for key in "${!VERSIONS[@]}"; do
    echo -e "${YELLOW} $key. ${VERSIONS[$key]}${NC}"
  done
  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "请输入版本编号 [1-8]: " version_choice

  if [[ -n "${VERSIONS[$version_choice]}" ]]; then
    CONFIG["VERSION_ID"]="$version_choice"
    CONFIG["IMAGE_NAME"]="${VERSIONS[$version_choice]%% -*}"
    save_config
    echo -e "${GREEN}版本已切换为: ${VERSIONS[$version_choice]}${NC}"
    sleep 2
  else
    echo -e "${RED}无效选择!${NC}"
    sleep 1
  fi
}

# 显示配置管理菜单
show_config_menu() {
  clear
  echo -e "${CYAN}=============================================${NC}"
  echo -e "${GREEN}          当前配置管理          ${NC}"
  echo -e "${CYAN}=============================================${NC}"
  echo -e " 1. 数据目录: ${CONFIG[BASE_DIR]}"
  echo -e " 2. 管理端口: ${CONFIG[PORT1]}"
  echo -e " 3. AList端口: ${CONFIG[PORT2]}"
  echo -e " 4. 网络模式: ${CONFIG[NETWORK]}"
  echo -e " 5. 重启策略: ${CONFIG[RESTART]}"
  echo -e " 6. 返回主菜单"
  echo -e "${CYAN}---------------------------------------------${NC}"
  read -p "选择要修改的配置 [1-6]: " config_choice

  case $config_choice in
    1)
      read -p "输入新的数据目录 [${CONFIG[BASE_DIR]}]: " new_dir
      [[ -n "$new_dir" ]] && CONFIG[BASE_DIR]="$new_dir"
      ;;
    2)
      read -p "输入新的管理端口 [${CONFIG[PORT1]}]: " new_port
      [[ -n "$new_port" ]] && CONFIG[PORT1]="$new_port"
      ;;
    3)
      read -p "输入新的AList端口 [${CONFIG[PORT2]}]: " new_port
      [[ -n "$new_port" ]] && CONFIG[PORT2]="$new_port"
      ;;
    4)
      read -p "输入网络模式 (bridge/host) [${CONFIG[NETWORK]}]: " new_net
      [[ -n "$new_net" ]] && CONFIG[NETWORK]="$new_net"
      ;;
    5)
      read -p "输入重启策略 (always/unless-stopped/no) [${CONFIG[RESTART]}]: " new_restart
      [[ -n "$new_restart" ]] && CONFIG[RESTART]="$new_restart"
      ;;
  esac

  if [[ "$config_choice" != "6" ]]; then
    save_config
    echo -e "${GREEN}配置已更新!${NC}"
    sleep 1
  fi
}

check_environment
load_config

# 主循环
interactive_mode() {
  load_config
  while true; do
    show_menu
    local container_name=$(get_container_name)

    case $choice in
      1)
        install_container
        ;;
      2)
        if docker inspect "$container_name" &>/dev/null; then
          docker start "$container_name"
          echo -e "${GREEN}容器已启动${NC}"
          sleep 1
        else
          echo -e "${RED}容器不存在，请先安装${NC}"
          sleep 1
        fi
        ;;
      3)
        if docker inspect "$container_name" &>/dev/null; then
          docker stop "$container_name"
          echo -e "${GREEN}容器已停止${NC}"
          sleep 1
        else
          echo -e "${YELLOW}容器不存在${NC}"
          sleep 1
        fi
        ;;
      4)
        if docker inspect "$container_name" &>/dev/null; then
          docker restart "$container_name"
          echo -e "${GREEN}容器已重启${NC}"
          sleep 1
        else
          echo -e "${RED}容器不存在，请先安装${NC}"
          sleep 1
        fi
        ;;
      5)
        if docker inspect "$container_name" &>/dev/null; then
          echo -e "${CYAN}容器状态:${NC}"
          docker ps -a --filter "name=$container_name"
          echo -e "\n${CYAN}资源使用:${NC}"
          docker stats --no-stream "$container_name"
          read -n 1 -s -r -p "按任意键继续..."
        else
          echo -e "${YELLOW}容器未运行${NC}"
          sleep 1
        fi
        ;;
      6)
        if docker inspect "$container_name" &>/dev/null; then
          docker logs -f "$container_name"
        else
          echo -e "${YELLOW}容器未运行${NC}"
          sleep 1
        fi
        ;;
      7)
        if docker inspect "$container_name" &>/dev/null; then
          docker stop "$container_name" && docker rm "$container_name"
          echo -e "${GREEN}容器已卸载${NC}"
          sleep 1
        else
          echo -e "${YELLOW}容器不存在${NC}"
          sleep 1
        fi
        ;;
      8)
        show_version_menu
        ;;
      9)
        show_config_menu
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
  load_config
  local container_name=$(get_container_name)

  case "$1" in
    install)
      install_container
      ;;
    start)
      docker start "$container_name"
      ;;
    stop)
      docker stop "$container_name"
      ;;
    restart)
      docker restart "$container_name"
      ;;
    status)
      docker ps -a --filter "name=$container_name"
      ;;
    logs)
      docker logs -f "$container_name"
      ;;
    uninstall)
      docker stop "$container_name" && docker rm "$container_name"
      ;;
    menu)
      interactive_mode
      ;;
    *)
      echo -e "${RED}未知命令: $1${NC}"
      echo "可用命令: install, start, stop, restart, status, logs, uninstall, menu"
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