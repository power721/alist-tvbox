if ! grep -q '我的PikPak分享/' /index/index.video.txt; then
	cat /index/index.zhao.txt >> /index/index.video.txt
fi
