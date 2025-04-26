if [ $# -gt 0 ]; then
  remote=$1
  wget -t 2 https://gh-proxy.net/https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.zip || \
  wget -t 2 https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.zip -O index.zip || \
  wget -t 2 http://har01d.org/index.zip -O index.zip
  if [ ! -f index.zip ]; then
    echo "Failed to download index compressed file, the index file upgrade process has aborted"
    exit 1
  else
    unzip -o -q -P abcd index.zip
    cat /index/index.share.txt >> index.video.txt
    cat index.video.txt index.book.txt index.music.txt index.non.video.txt >/index/index.txt
    mv index*.txt /index/
    echo $(date) "update index successfully, your new version.txt is $remote"
    echo "$remote" >/index/version.txt
    rm -f rm index.*
  fi
fi

if ! grep -q '我的PikPak分享/' /index/index.video.txt; then
	cat /index/index.zhao.txt >> /index/index.video.txt
fi
