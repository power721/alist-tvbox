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
    {"key":"Alist","name":"Alist┃转发","type":1,"api":"http://ip:8080/vod","searchable":0,"quickSearch":0,"filterable":0}
  ],
  "rules": [
    {"host":"pdsapi.aliyundrive.com","rule":["/redirect"]},
    {"host":"*","rule":["http((?!http).){12,}?\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a)\\?.*"]},
    {"host":"*","rule":["http((?!http).){12,}\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a)"]}
  ]
}
```

# Support Search
Can only search data from xiaoyaliu/alist now.

Thanks xiaoyaliu/alist to provide the index file.

### Docker Container
Use the following docker to provide data from xiaoyaliu/alist.
```bash
docker run -d -p 5244:80 --restart=always --name=alist haroldli/alist
# or use your own ali token
docker run -d -p 5244:80 --restart=always -e ALI_TOKEN=xxx --name=alist haroldli/alist
```

```bash
curl -s http://d.har01d.cn/update_xiaoya.sh | bash
```

### TvBox Config
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
