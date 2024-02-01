# alist-tvbox
AList proxy server for TvBox, support playlist and search.

[中文文档](doc/README_zh.md)

# Build
```bash
mvn clean package
```

# Run
```bash
sudo bash -c "$(curl -fsSL https://d.har01d.cn/update_xiaoya.sh)"
```
```bash
java -jar target/alist-tvbox-1.0.jar
```

# Docker
```bash
./build.sh
docker run -d -p 4567:4567 --restart=always --name=alist-tvbox alist-tvbox
```
Or run container from Docker hub.
```bash
docker run -d -p 4567:4567 --restart=always --name=alist-tvbox haroldli/alist-tvbox
```
```bash
docker run -d -p 4567:4567 -p 5344:80 -e ALIST_PORT=5344 -v /etc/xiaoya:/data --restart=always --name=xiaoya-tvbox haroldli/xiaoya-tvbox:latest
```
username: admin

password: admin

# TvBox Config
Use this config url `http://ip:4567/sub/0`.

### Customize
Backed URL support multiple values, use comma as separator.
e.g.: disable 2 sites by key, change 1 site name by key, add new site.
```json
{
    "sites-blacklist": ["csp_Bili", "csp_Biliych"],
    "sites": [
        {
          "key": "js豆瓣",
          "name": "js豆瓣"
        },
        {
          "key": "测试",
          "name": "测试",
          "type": 3,
          "api": "/tvbox/libs/drpy.min.js",
          "searchable": 2,
          "quickSearch": 0,
          "filterable": 1
        }
    ],
    "parses": [
        {
            "name":"测试1",
            "type":3,
            "url":"测试"
        }
    ]
}
```
