# alist-tvbox
AList proxy server for TvBox, support playlist and search.

# Build
```bash
mvn clean package
```

# Run
```bash
curl -s http://d.har01d.cn/update_new.sh | sudo bash
```
```bash
java -jar target/alist-tvbox-1.0.jar --server.port=5678
```

# Deploy
```bash
scp target/alist-tvbox-1.0.jar user@your-server:~/atv.jar
scp config/install-service.sh user@your-server:~
# login to your server
./install-service.sh
```

# Docker
```bash
./build.sh
docker run -d -p 5678:8080 -e ALIST_URL=http://IP:5244 --restart=always --name=alist-tvbox alist-tvbox
```
Or run container from Docker hub.
```bash
docker run -d -p 5678:8080 --restart=always --name=alist-tvbox haroldli/alist-tvbox
```
```bash
docker run -d -p 5678:8080 -p 5244:80 -v /etc/xiaoya:/data --restart=always --name=xiaoya-tvbox haroldli/xiaoya-tvbox:latest
```
username: admin

password: admin
# TvBox Config
```json
{
  "sites": [
    {"key":"Alist","name":"Alist┃转发","type":1,"api":"http://ip:5678/vod","searchable":1,"quickSearch":1,"filterable":1}
  ],
  "rules": [
    {"host":"pdsapi.aliyundrive.com","rule":["/redirect"]},
    {"host":"*","rule":["http((?!http).){12,}?\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a|ts)\\?.*"]},
    {"host":"*","rule":["http((?!http).){12,}\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a|ts)"]}
  ]
}
```

Or use this config url `http://ip:5678/sub/1`.

Change the backend config url in application.yaml
```yaml
app:
  configUrl: https://hutool.ml/tang
```
