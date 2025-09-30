![GitHub Repo stars](https://img.shields.io/github/stars/power721/alist-tvbox?style=for-the-badge)
![GitHub forks](https://img.shields.io/github/forks/power721/alist-tvbox?style=for-the-badge)
![GitHub contributors](https://img.shields.io/github/contributors/power721/alist-tvbox?style=for-the-badge)
![GitHub repo size](https://img.shields.io/github/repo-size/power721/alist-tvbox?style=for-the-badge)
![GitHub issues](https://img.shields.io/github/issues/power721/alist-tvbox?style=for-the-badge)
![Docker Pulls - xiaoya-tvbox](https://img.shields.io/docker/pulls/haroldli/xiaoya-tvbox?style=for-the-badge)
![Docker Pulls - alist-tvbox](https://img.shields.io/docker/pulls/haroldli/alist-tvbox?style=for-the-badge)

# AList-TvBox
AList proxy server for TvBox, support playlist and search.

[中文文档](doc/README_zh.md)

[OpenList](https://github.com/power721/PowerList)

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
  "sites": [
    {
      "key": "js豆瓣",
      "name": "js豆瓣"
    },
    {
      "key": "测试",
      "name": "测试",
      "type": 3,
      "api": "ATV_ADDRESS/tvbox/libs/drpy.min.js",
      "searchable": 2,
      "quickSearch": 0,
      "filterable": 1
    }
  ],
  "parses": [
    {
      "name": "测试1",
      "type": 3,
      "url": "测试"
    }
  ],
  "blacklist": {
    "sites": [
      "csp_Bili",
      "csp_Biliych"
    ],
    "parses": [
      "聚合"
    ]
  }
}
```
