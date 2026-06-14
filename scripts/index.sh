if [ $# -gt 0 ]; then
  remote=$1

  # 读取多代理列表，逐个尝试（最多 5 个）
  downloaded=false
  if [ -f "/data/github_proxy.txt" ]; then
    proxies=$(head -n 5 "/data/github_proxy.txt" 2>/dev/null | grep -v '^$')
    if [ -n "$proxies" ]; then
      echo "$proxies" | while IFS= read -r proxy; do
        if [ -n "$proxy" ]; then
          if wget -t 1 "${proxy}https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.zip" -O index.zip 2>/dev/null; then
            exit 0
          fi
        fi
      done
      # 检查是否下载成功
      [ -f index.zip ] && [ -s index.zip ] && downloaded=true
    fi
  fi

  # 所有代理失败，尝试直连和备用地址
  if [ "$downloaded" = false ]; then
    wget -t 2 https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.zip -O index.zip || \
    wget -t 2 https://d.har01d.cn/index.zip -O index.zip
  fi

  if [ ! -f index.zip ]; then
    echo "Failed to download index compressed file, the index file upgrade process has aborted"
    exit 1
  else
    unzip -o -q -P abcd index.zip
    cat /index/index.share.txt >> index.video.txt
    sed -i 's/🏷️我的115分享/我的115分享/' /index/index.115.txt
    sed -i 's/🌀我的夸克分享/我的夸克分享/' /index/index.quark.txt
    cat index.video.txt index.comics.txt index.docu.txt index.daily.txt index.movie.txt index.tv.txt | sort -u >index.merged.txt
    cat index.merged.txt index.book.txt index.music.txt index.non.video.txt index.reality.txt | sort -u >/index/index.txt
    mv index*.txt /index/
    echo $(date) "update index successfully, your new version.txt is $remote"
    echo "$remote" >/index/version.txt
    rm -f rm index.*
  fi
fi
