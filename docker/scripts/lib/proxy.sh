#!/bin/sh
# 代理配置管理

# 配置环境变量代理
setup_env_proxy() {
  if [ -r /data/proxy.txt ]; then
    proxy_url=$(head -n1 /data/proxy.txt)
    if [ -n "$proxy_url" ]; then
      export HTTP_PROXY=$proxy_url
      export HTTPS_PROXY=$proxy_url
      export no_proxy="*.aliyundrive.com"
      log_info "Proxy configured: $proxy_url"
    fi
  fi
}

# 加载自定义环境变量
load_custom_env() {
  if [ -r /data/env ]; then
    log_info "Loading custom environment from /data/env"
    cp /data/env /etc/profile.d/custom_env.sh
    chmod +x /etc/profile.d/custom_env.sh
  fi
}
