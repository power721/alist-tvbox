# alist-tvbox
AList proxy server for TvBox, support playlist and search.

# Configure
Set `app.sites` in the file src/main/resources/application.yaml

# Build
```bash
mvn clean package
```

# Run
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

# Index And Search
```http request
POST http://localhost:5678/index
Content-Type: application/json

{
  "siteId": 1,
  "indexName": "index.xiaoya",
  "excludeExternal": false,
  "paths": [
    "/电视剧",
    "/动漫",
    "/综艺",
    "/纪录片",
    "/电影",
    "/音乐"
  ],
  "stopWords": [
  ],
  "excludes": [
  ],
  "maxDepth": 10
}

```

application.yaml
```yaml
app:
  sites:
    - name: 小雅
      url: http://alist.xiaoya.pro
      searchable: true
      indexFile: /the/path/to/index.xiaoya.txt
    - name: Har01d
      url: http://alist.har01d.cn
      searchable: true
      indexFile: http://d.har01d.cn/index.full.zip
```
