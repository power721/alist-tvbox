cd /var/www/alist

if [ $# -gt 0 ];then
  echo "sync all data files"
  wget https://raw.githubusercontent.com/xiaoyaliu00/data/main/quarkshare_list.txt -O quarkshare_list.txt
  wget https://raw.githubusercontent.com/xiaoyaliu00/data/main/115share_list.txt -O 115share_list.txt
  wget https://raw.githubusercontent.com/xiaoyaliu00/data/main/pikpakshare_list.txt -O pikpakshare_list.txt
  wget https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt -O version.txt
  wget https://raw.githubusercontent.com/xiaoyaliu00/data/main/tvbox.zip -O tvbox.zip
  wget https://raw.githubusercontent.com/xiaoyaliu00/data/main/update.zip -O update.zip
  wget https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.zip -O index.zip
  wget https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.video.zip -O index.video.zip
  cp version.txt index_version
  #zip -d update.zip meta.sql
  exit
fi

wget https://raw.githubusercontent.com/xiaoyaliu00/data/main/version.txt -O version.txt

LOCAL="0.0"
if [ -f index_version ]; then
  LOCAL=$(head -n 1 <index_version)
fi

REMOTE=$(head -n 1 <version.txt)

if [ "$LOCAL" != "$REMOTE" ]; then
  wget https://raw.githubusercontent.com/xiaoyaliu00/data/main/tvbox.zip -O tvbox.zip
  wget https://raw.githubusercontent.com/xiaoyaliu00/data/main/update.zip -O update.zip
  #zip -d update.zip meta.sql

  echo "download index.zip" && \
  wget https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.zip -O index.zip
  wget https://raw.githubusercontent.com/xiaoyaliu00/data/main/index.video.zip -O index.video.zip
fi

cp version.txt index_version
