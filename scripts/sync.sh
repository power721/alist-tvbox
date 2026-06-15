cd /var/www/alist

# 通用下载函数，支持多代理 fallback
download_with_proxy() {
  url=$1
  output=$2

  if [ -f "/data/github_proxy.txt" ]; then
    proxy_count=0
    while IFS= read -r proxy || [ -n "$proxy" ]; do
      proxy_count=$((proxy_count + 1))
      [ "$proxy_count" -gt 5 ] && break
      if [ -n "$proxy" ]; then
        candidate="${proxy}${url}"
      else
        candidate="${url}"
      fi
      if wget -t 1 "${candidate}" -O "${output}" 2>/dev/null; then
        return 0
      fi
    done < "/data/github_proxy.txt"
  fi

  # 所有代理失败，尝试直连
  wget -t 2 "${url}" -O "${output}" 2>/dev/null
  return $?
}

if [ $# -gt 0 ];then
  echo "sync all data files"
  download_with_proxy https://raw.githubusercontent.com/xiaoyaliu00/data/main/quarkshare_list.txt quarkshare_list.txt
  download_with_proxy https://raw.githubusercontent.com/xiaoyaliu00/data/main/115share_list.txt 115share_list.txt
  download_with_proxy https://raw.githubusercontent.com/xiaoyaliu00/data/main/pikpakshare_list.txt pikpakshare_list.txt
  download_with_proxy https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt version.txt
  download_with_proxy https://raw.githubusercontent.com/xiaoyaliu00/data/main/tvbox.zip tvbox.zip
  download_with_proxy https://raw.githubusercontent.com/xiaoyaliu00/data/main/update.zip update.zip
  download_with_proxy https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.zip index.zip
  download_with_proxy https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.video.zip index.video.zip
  cp version.txt index_version
  #zip -d update.zip meta.sql
  exit
fi

download_with_proxy https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt version.txt

LOCAL="0.0"
if [ -f index_version ]; then
  LOCAL=$(head -n 1 <index_version)
fi

REMOTE=$(head -n 1 <version.txt)

if [ "$LOCAL" != "$REMOTE" ]; then
  download_with_proxy https://raw.githubusercontent.com/xiaoyaliu00/data/main/tvbox.zip tvbox.zip
  download_with_proxy https://raw.githubusercontent.com/xiaoyaliu00/data/main/update.zip update.zip
  #zip -d update.zip meta.sql

  echo "download index.zip" && \
  download_with_proxy https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.zip index.zip
  download_with_proxy https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.video.zip index.video.zip
fi

cp version.txt index_version
