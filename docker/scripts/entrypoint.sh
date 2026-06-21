#!/bin/sh
# 统一的容器入口脚本

set -e

# 加载依赖
. /docker/scripts/lib/common.sh
. /docker/scripts/lib/proxy.sh

log_info "=========================================="
log_info "Container Starting"
log_info "=========================================="

# 加载自定义环境变量
load_custom_env

# 配置代理
setup_env_proxy

# 确保脚本可执行
chmod a+x /docker/scripts/*.sh
chmod a+x /docker/scripts/lib/*.sh

# 确保日志目录存在
ensure_dir /data/log

# 根据 INSTALL 环境变量选择初始化脚本
case "$INSTALL" in
  xiaoya|hostmode)
    log_info "Running xiaoya mode initialization"
    /docker/scripts/init-xiaoya.sh 2>&1 | tee /data/log/init.log
    ;;
  new|docker|*)
    log_info "Running standard mode initialization"
    /docker/scripts/init-alist.sh 2>&1 | tee /data/log/init.log
    ;;
esac

# 启动服务
log_info "Starting services..."

# 如果有 httpd（xiaoya 模式）
if [ -f /bin/busybox-extras ] && [ -n "$1" ] && [ "$1" != "--spring.profiles.active"* ]; then
  HTTP_PORT=$1
  shift
  log_info "Starting httpd on port $HTTP_PORT"
  /bin/busybox-extras httpd -p "$HTTP_PORT" -h /www &
fi

# 如果有 nginx（xiaoya 模式）
if [ -f /usr/sbin/nginx ]; then
  log_info "Starting nginx"
  /usr/sbin/nginx
fi

mkdir -p /data/atv/config/

# 启动 Java 应用
log_info "Starting AList-TVBox application"
exec /jre/bin/java "$MEM_OPT" -Duser.timezone=Asia/Shanghai -Dspring.config.additional-location=file:/data/atv/config/ -cp BOOT-INF/classes:BOOT-INF/lib/* cn.har01d.alist_tvbox.AListApplication "$@"
