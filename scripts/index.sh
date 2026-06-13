if [ $# -gt 0 ]; then
  remote=$1
  gh_proxy=$(head -n 1 "/data/github_proxy.txt" 2>/dev/null || echo "")
  wget -t 2 ${gh_proxy}https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.zip || \
  wget -t 2 https://d.har01d.cn/index.zip -O index.zip
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
