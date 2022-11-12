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
