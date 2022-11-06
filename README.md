# alist-tvbox
AList proxy server for TvBox, support playlist

# Configure
Configure the AList server url in application.yaml

# Docker
```bash
./build.sh
docker run -d -p 5244:8080 --restart=always --name=alist-tvbox alist-tvbox
```

# Deploy
```bash
java -jar target/alist-tvbox-1.0.jar
```

# TvBox Config
```json
{"key":"test","name":"Alist测试","type":1,"api":"http://192.168.50.10:5244/vod","searchable":2,"quickSearch":0,"filterable":0}
```
