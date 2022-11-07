# alist-tvbox
AList 3.0 proxy server for TvBox, support playlist.

# Configure
Configure the AList server url `app.url` in application.yaml

# Deploy
```bash
java -jar target/alist-tvbox-1.0.jar
java -jar target/alist-tvbox-1.0.jar --app.url=https://yourdomain.com
```

# Docker
```bash
./build.sh
docker run -d -p 5244:8080 --restart=always --name=alist-tvbox alist-tvbox
```

# TvBox Config
```json
{"key":"Alist","name":"Alist┃转发","type":1,"api":"http://ip:8080/vod","searchable":2,"quickSearch":0,"filterable":0}
```
