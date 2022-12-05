# alist-tvbox
AList proxy server for TvBox, support playlist.

# Configure
Set `app.sites` in the file src/main/resources/application.yaml

# Build
```bash
mvn clean package
```

# Run
```bash
java -jar target/alist-tvbox-1.0.jar
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
docker run -d -p 8080:8080 --restart=always --name=alist-tvbox alist-tvbox
```
Or run container from Docker hub.
```bash
docker run -d -p 8080:8080 --restart=always --name=alist-tvbox haroldli/alist-tvbox
```

# TvBox Config
```json
{
  "sites": [
    {"key":"Alist","name":"Alist┃转发","type":1,"api":"http://ip:8080/vod","searchable":1,"quickSearch":0,"filterable":0}
  ],
  "rules": [
    {"host":"pdsapi.aliyundrive.com","rule":["/redirect"]},
    {"host":"*","rule":["http((?!http).){12,}?\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a)\\?.*"]},
    {"host":"*","rule":["http((?!http).){12,}\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a)"]}
  ]
}
```

# Index And Search
```http request
POST http://localhost:8080/index
Content-Type: application/json

{
  "site": "小雅",
  "collection": [
    "/电视剧",
    "/动漫",
    "/综艺",
    "/纪录片"
  ],
  "single": [
    "/电影",
    "/音乐"
  ],
  "maxDepth": 10
}

```

```yaml
app:
  sites:
    - name: 小雅
      url: http://alist.xiaoya.pro
      searchable: true
      indexFile: /the/path/to/index.txt
```
