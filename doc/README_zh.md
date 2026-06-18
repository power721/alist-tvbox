# AList-TvBox

AList-TvBox 是一个功能强大的云存储聚合工具，专为 TvBox 客户端设计，提供多媒体搜索、直播流聚合等完整解决方案。

## 快速开始

1. 准备工作：安装Docker
2. 安装AList-TvBox：
    ```bash
    sudo bash -c "$(curl -fsSL http://d.har01d.cn/alist-tvbox.sh)"
    ```
3. 打开管理界面：http://your-ip:4567/#/accounts
4. 获取并填写阿里token、开放token
5. 在订阅页面复制TvBox订阅地址，输入到TvBox配置

## 主要功能

### 媒体聚合
- **多平台支持**: AList、Emby、Jellyfin、飞牛影视、BiliBili、YouTube
- **海报墙模式**: 瀑布流展示，支持分类筛选
- **网络直播**: 虎牙、斗鱼、B站、网易CC、快手、抖音实时直播流
- **电报搜索**: 频道搜索、网页搜索、豆瓣数据库浏览

### 云存储管理
- **支持网盘账号**: 阿里云盘、百度网盘、夸克、UC、115、123、天翼、移动、迅雷、PikPak、光鸭
- **支持分享**: 上述所有网盘的分享链接解析
- **虚拟存储**: 本地存储、STRM存储、UrlTree虚拟存储、别名路径
- **自动刷新**: 阿里Token自动续期
- **负载均衡**: 多账号轮询使用
- **加速代理**: AList多线程代理加速播放

### TvBox订阅系统
- **订阅配置**: 自定义TvBox配置，支持站点白名单/黑名单
- **订阅聚合**: 聚合多个订阅源，统一配置管理
- **安全订阅**: Token认证机制，防止订阅地址泄露
- **Python插件**: 通过 csp_PyProxy 加载 Python 爬虫，支持本地代理加速
- **订阅源管理**: 拖拽排序、启用/禁用、自定义order字段控制顺序

### 索引与刮削
- **构建索引**: 支持增量索引、压缩索引、自定义深度
- **定时任务**: 每天22点及黄金时段自动索引
- **TMDB刮削**: 支持TMDB API刮削电影元数据
- **豆瓣刮削**: 集成豆瓣数据，提取电影信息
- **路径控制**: 支持 `-` 屏蔽、`+` 仅搜索、`>` 重置路径

### 插件与扩展
- **Python爬虫插件**: 扩展TvBox搜索和播放能力
- **插件过滤器**: 自定义Python过滤规则，支持接收播放元数据
- **远程导入**: 从远程仓库批量导入插件
- **运行模式**: Java代理模式 / Python原生模式

### 离线下载
- **支持网盘**: 115云盘、光鸭云盘、迅雷云盘
- **自动配置**: 自动创建 alist-tvbox-offline 临时目录
- **TvBox集成**: 播放页面直接触发离线下载

### 多租户与权限
- **多租户**: 配置包含/排除路径规则控制不同用户访问范围
- **用户管理**: 普通用户仅能搜索、播放、观看直播
- **ACL控制**: 基于Token的访问控制列表
- **角色系统**: ADMIN / USER / CLIENT 三级权限

### 其他功能
- **设备管理**: 扫描局域网TvBox设备，推送内容，同步观看历史
- **视频管理**: 评分、重命名、移动、删除
- **观看历史**: 记录与管理观看历史
- **盘搜**: 支持链接检测，批量验证分享链接有效性
- **在线日志**: 实时查看应用日志
- **数据库备份**: 每天6点自动备份，支持手动导出恢复
- **WebDAV**: 内置WebDAV服务，默认用户名 guest 密码 alist_tvbox（开启强制登录后使用AList账号）

## 安装部署

### Docker 一键安装（推荐）

不需要再安装小雅版Docker。

如果找不到bash就替换为sh。如果找不到sudo，就用root账号登录，去掉sudo后运行。

```bash
sudo bash -c "$(curl -fsSL http://d.har01d.cn/alist-tvbox.sh)"
```

OpenWrt去掉sudo，或者已经是root账号：
```bash
bash -c "$(curl -fsSL http://d.har01d.cn/alist-tvbox.sh)"
```

如果没有安装curl:
```bash
wget http://d.har01d.cn/alist-tvbox.sh; sudo bash ./alist-tvbox.sh
```

### Docker 镜像版本

#### 小雅集成版
内置了小雅的阿里分享和115分享资源。

```bash
docker run -d \
  -p 4567:4567 \
  -p 5344:80 \
  -e ALIST_PORT=5344 \
  -v /opt/alist-tvbox:/data \
  -v /opt/alist-tvbox/www-static:/www/static \
  --restart=always \
  --name=xiaoya-tvbox \
  haroldli/xiaoya-tvbox:latest
```

#### 小雅集成版 host 网络模式
使用host网络模式运行。

```bash
docker run -d \
  --network=host \
  -v /opt/alist-tvbox:/data \
  -v /opt/alist-tvbox/www-static:/www/static \
  --restart=always \
  --name=xiaoya-tvbox \
  haroldli/xiaoya-tvbox:hostmode
```


#### 纯净版
没有内置分享数据、可以直接访问AList管理界面。

```bash
docker run -d \
  -p 4567:4567 \
  -v /opt/alist-tvbox:/data \
  -v /opt/alist-tvbox/www-static:/www/static \
  --restart=always \
  --name=alist-tvbox \
  haroldli/alist-tvbox:latest
```

#### 容器端口

| 版本 | 服务 | 端口 | 说明 |
|------|------|------|------|
| 纯净版 / 小雅集成版 | 管理应用 | 4567 | 管理后台 |
| 纯净版 / 小雅集成版 | AList | 5244 | 默认映射为 5344 |
| 纯净版 / 小雅集成版 | nginx | 80 | Web 服务 |
| 纯净版 / 小雅集成版 | httpd | 81 | HTTP 服务 |
| 小雅 host 网络版 | 管理应用 | 4567 | 管理后台 |
| 小雅 host 网络版 | AList | 5234 | AList 服务 |
| 小雅 host 网络版 | nginx | 5678 | Web 服务 |
| 小雅 host 网络版 | httpd | 5233 | HTTP 服务 |

### NAS 部署 (群辉/威联通等)

对于群辉等NAS系统，请挂载Docker的`/data`目录到群辉文件系统，否则数据不会保留。

#### 创建容器
![创建容器](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_docker1.png)

#### 目录映射
![目录映射](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_docker2.png)

#### 端口映射
![端口映射](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_docker3.png)

#### 环境变量
![环境变量](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_docker4.png)

### 自动更新

#### 定时任务更新

使用root用户创建corntab定时任务
```bash
wget http://d.har01d.cn/alist-tvbox.sh -O /opt/alist-tvbox.sh
chmod a+x /opt/alist-tvbox.sh
crontab -l | { cat; echo "0 2 * * * /opt/alist-tvbox.sh update -y"; } | crontab -
```
每天凌晨2点检查更新并重启应用。

### 海报展示
#### 浏览目录
![浏览目录](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/poster1.jpg)
#### 搜索界面
![搜索界面](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/poster2.jpg)
#### 播放界面
![播放界面](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/poster3.jpg)

## 多用户
在用户页面创建普通用户。

普通用户只能在网页搜索、播放和观看网络直播。

网页播放使用用户名作为安全订阅Token，也就是说用户名在ACL页面可以当作Token使用。

### 多租户
在管理页面创建租户，可以配置包含/排除路径规则来控制不同用户的访问范围。

## 管理
打开管理网页：http://your-ip:4567/ 

默认用户名：admin 密码：admin

点击右上角菜单，进入用户界面修改用户名和密码。

### 站点
![站点列表](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sites.png)

默认添加了站点：`http://localhost`，如果AList配置有域名，自行修改地址。否则保持`http://localhost`！
因为小雅用80端口代理了容器内的AList 5244端口。
管理程序运行在同一个容器内，能够直接访问80端口。

访问AList，请加端口，http://your-ip:5344/ 。使用Docker映射的端口，默认是5344.

自己可以添加三方站点，功能与xiaoya的套娃类似。会自动识别版本，如果不能正确识别，请手动配置版本。

选择TvBox第二个站源，观看三方站点内容。或者在我的套娃观看。

![添加站点](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_site_config.png)

如果AList开启了强制登录，会自动填写认证token。

![站点数据](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_site_data.png)

### Emby站点
在Emby页面添加Emby站点url和帐号。

在TvBox选择第五个站源观看。

![Emby站源](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_emby.jpg)

### Jellyfin站点
在Jellyfin页面添加Jellyfin站点url和帐号。

与Emby类似，支持浏览和播放Jellyfin媒体库内容。

### 飞牛影视
在飞牛页面添加飞牛影视站点url和帐号。

支持浏览、搜索、播放飞牛影视内容，自动记录播放进度。

### 账号
![账号列表](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_account.png)

第一次启动会自动读取/data/mytoken.txt,/data/myopentoken.txt里面的内容，以后这些文件不再生效。
自动创建转存文件夹，不需要再填写转存文件夹ID。

修改主账号后不需要重启AList服务。

![账号详情](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_account_detail.png)

#### 网盘帐号
网盘帐号在帐号页面添加。

支持的网盘类型：夸克网盘、UC网盘、夸克TV、UC TV、115云盘、迅雷云盘、天翼云盘、移动云盘、123网盘、百度网盘、光鸭云盘。

夸克网盘Cookie获取方式： https://alist.nn.ci/zh/guide/drivers/quark.html

UC网盘Cookie获取方式： https://alist.nn.ci/zh/guide/drivers/uc.html

115网盘Cookie获取方式： https://alist.nn.ci/zh/guide/drivers/115.html

迅雷云盘需要输入用户名和密码登录，用户名格式：`+86 12345678900`（+86后面加一个空格）。

天翼云盘、123网盘需要输入用户名和密码。

网盘分享在资源页面添加。

115网盘开启本地代理后才能使用webdav播放。

#### 加速代理
有些网盘资源需要发送HTTP请求头或者Cookie才能播放。如果播放器支持（如影视），直接返回播放地址和HTTP请求头。

如果播放器不支持（如网页播放器），需要使用AList代理访问。网页播放强制使用代理播放。

AList代理具有多线程加速。也可以在网盘帐号开启加速代理，使影视播放加速。

- 阿里需要HTTP请求头。
- 夸克、UC需要Cookie。
- 115需要Cookie。
- 其它网盘使用302直接播放原始地址。

![加速代理](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_account_proxy.png)

#### 网盘帐号负载均衡
在高级配置开启网盘帐号负载均衡。

如果添加了多个同一类型的网盘帐号，观看分享会轮流使用网盘帐号获取播放地址。

阿里帐号如果同时存在会员帐号和非会员帐号，只会使用会员帐号。

开启后主帐号不再生效。

#### 网盘账号配置
点击账号页面右上角的"配置"按钮，可以设置全局代理和离线下载。

全局代理配置会下发到爬虫插件的 `local_proxy_config`，可分别为阿里、夸克、UC、115、123、移动、百度、光鸭设置是否启用、并发数和分片大小。

离线下载支持 115 云盘、光鸭云盘、迅雷云盘。
需要先添加对应网盘账号，并确保账号名称对应的挂载目录不为空。
保存离线下载配置时，系统会在所选账号的目录下自动创建或复用 `alist-tvbox-offline` 临时目录。
播放页或客户端离线下载接口提交任务后，会把完成后的资源作为 TvBox 详情返回。

### 订阅
tvbox/my.json和juhe.json不能在TvBox直接使用，请使用订阅地址！

![订阅列表](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sub.png)

![添加订阅](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sub_config.png)

添加订阅支持多个URL，用逗号隔开。定制部分基本和TvBox的配置json一致，添加了站点白名单`sites-whitelist`和黑名单`blacklist`。

定制属于高级功能，不懂TvBox配置格式不要轻易改动。

站点`key`是必须的，其它字段可选。对于lives，rules，parses，doh类型，`name`字段是必须的。

站点名称可以加前缀，通过订阅URL前面加前缀，使用`@`分割。比如：`饭@http://饭太硬.top/tv,菜@https://tv.菜妮丝.top`

#### Python爬虫插件
订阅页面添加的 Python 爬虫插件会通过内置 `spring.jar` 里的 `csp_PyProxy` 加载，不再直接把站点 `api` 指向 `Atvp.py`。

生成后的站点结构类似：
```json
{
  "key": "YouTube",
  "name": "YouTube",
  "type": 3,
  "api": "csp_PyProxy",
  "jar": "ATV_ADDRESS/spring.jar",
  "ext": "base64({\"loader\":\"ATV_ADDRESS/Atvp.py\",\"api\":\"ATV_ADDRESS\",\"source\":\"...\",\"token\":\"...\",\"local_proxy_config\":{\"ALI\":{\"enabled\":true,\"concurrency\":20,\"chunk_size\":1024}}})"
}
```

`loader`、`local_proxy_config` 和其他 Python 侧配置都会一起编码进 `ext`。配置为 `{}` 或对应网盘类型未开启时，不会启用播放加速代理。

替换功能：

在配置页面->高级设置里面找到阿里Token地址，然后在订阅-定制里面自替换token。

```json
{
  "sites": [
    {
      "key": "玩偶哥哥",
      "name": "👽玩偶哥哥┃4K弹幕",
      "type": 3,
      "api": "csp_WoGG",
      "searchable": 1,
      "quickSearch": 1,
      "changeable": 0,
      "ext": "http://127.0.0.1:9978/file/tvfan/token.txt+4k|auto|fhd$$$https://www.wogg.xyz/$$$弹",
      "jar": "https://fs-im-kefu.7moor-fs1.com/29397395/4d2c3f00-7d4c-11e5-af15-41bf63ae4ea0/1708249660012/fan.txt;md5;87d5916b7bb5c8acacac5490e802828e"
    }
  ],
  "lives": [
    {
      "name": "范明明•ipv6",
      "type": 0,
      "url": "https://github.moeyy.xyz/https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv6.m3u",
      "playerType": 1,
      "epg": "http://epg.112114.xyz/?ch={name}&date={date}",
      "logo": "https://epg.112114.xyz/logo/{name}.png"
    }
  ],
  "blacklist": {
    "sites": [
      "说明1",
      "说明2",
      "说明3",
      "说明4",
      "公告",
      "ext_live_protocol",
      "cc",
      "豆豆"
    ],
    "parses": [
      "聚合"
    ]
  }
}
```

![订阅预览](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sub_data.png)

#### 自定义站点顺序
在订阅定制中通过 `order` 字段控制站点顺序，数值越小越靠前。

内置源和插件从1000开始，订阅源从2000开始，未设置order的站点默认9000排在最后。

```json
{
  "sites": [
    {
      "key": "豆瓣",
      "order": 100
    },
    {
      "key": "YouTube",
      "order": 500
    }
  ]
}
```

#### 订阅源管理
在订阅页面可以统一管理所有订阅源，启用/禁用、重命名、拖拽排序。

内置订阅源：
- **csp_XiaoYa** - 小雅搜索源
- **csp_AList** - AList站点浏览
- **csp_BiliBili** - BiliBili视频
- **csp_Emby** - Emby媒体库
- **csp_Jellyfin** - Jellyfin媒体库
- **csp_FeiNiu** - 飞牛影视
- **csp_Live** - 网络直播
- **csp_TgDouBan** - 电报豆瓣搜索
- **csp_TgChannel** - 电报频道搜索
- **csp_TgWeb** - 电报网页搜索
- **csp_FishPanSou** - 鱼佬盘搜
- **csp_Push** - 推送

支持的订阅源类型：
- 内置小雅搜索源
- 自定义AList站点
- Emby站点
- Jellyfin站点
- 飞牛影视站点
- Python爬虫插件


### 插件管理
管理Python爬虫插件，扩展TvBox搜索和播放能力。

功能：
- 添加、编辑、删除插件（支持批量删除）
- 从远程仓库导入插件
- 刷新插件内容
- 调整插件顺序
- 配置插件运行模式（Java代理模式/Python原生模式）

Java代理模式使用本地加速代理，Python原生模式使用后端代理。

插件内容通过 `http://IP:4567/plugins/{token}/{id}.txt` 访问。

### 插件过滤器
对插件搜索结果进行过滤，支持自定义过滤规则。

功能：
- 添加、编辑、删除过滤器（支持批量删除）
- 按插件作用范围配置过滤器
- 支持过滤器自声明配置结构
- 过滤器可以接收播放元数据

过滤器内容通过 `http://IP:4567/plugin-filters/{token}/{id}.py` 访问。

### 离线下载
支持115云盘、光鸭云盘和迅雷云盘离线下载。

在账号页面点击"配置"，设置离线下载目标网盘类型和账号。系统会自动创建或复用 `alist-tvbox-offline` 临时目录。

TvBox播放页面可以触发离线下载，将资源下载到配置的网盘中。

TvBox客户端接口：`POST http://IP:4567/offline_download/{token}`

### 资源
第一次启动会自动读取/data/alishare_list.txt文件里面的分享内容，并保存到数据库，以后这个文件就不再生效。

可以在界面批量导入文件里面的分享内容，批量删除分享。

支持的分享类型：阿里云盘、PikPak、夸克、UC、115、123网盘、天翼、移动、迅雷、百度、光鸭分享，以及本地存储和STRM存储。

添加资源如果路径以/开头就会创建在根目录下。否则在/🈴我的阿里分享/下面。

系统会添加一些默认阿里分享资源，不能彻底删除。
![分享列表](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_shares.png)

### 海报墙模式
![海报](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_poster.jpg)
![海报1](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_poster1.jpg)

添加一个小雅站点并打开搜索功能。

![源](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_source.jpg)

可以自定义类别。在文件管理界面，添加一个文件/data/category.txt，内容是要显示的小雅目录。

可以自定义名称，冒号后面是自定义的名字。 在分类下面可以加子目录作为筛选条件，用两个空格开始。

<pre>
每日更新
  电视剧/国产剧
  电视剧/美剧
  美剧（已刮削）:美剧ℹ
</pre>

[示例文件](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/category.txt)

![类别](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_category.png)

![filter](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_filter.jpg)


### BiliBili
拖动行可以改变顺序，需要点击保存按钮才能生效。

打开、关闭显示开关后，需要点击保存按钮才能生效。

![BiliBili](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_bilibili.png)

登录后才能使用，TvBox第三个站源。

![扫码登录](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_bilibili_login.png)

或者使用已有的cookie登录。

系统会自动刷新B站Cookie，保持登录状态。

打开上报播放记录，B站才能看到播放记录。

![配置](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_bilibili_config.png)

添加搜索关键词作为一级分类：

![搜索](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_bilibili_search.png)

添加频道作为一级分类：

![频道](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_bilibili_channel.png)

### 网络直播
支持在网页播放网络直播，也提供 TvBox 兼容接口。

支持的平台：
- 虎牙
- 斗鱼
- B站直播
- 网易CC
- 快手
- 抖音

管理界面登录后进入"直播"页面，可以按平台浏览分类、翻页查看房间并播放直播流。

TvBox接口：
- `http://IP:4567/live`
- 开启安全订阅后使用 `http://IP:4567/live/TOKEN`

直播播放接口：`http://IP:4567/live-play` 或 `http://IP:4567/live-play/TOKEN`

虎牙单独兼容接口：
- `http://IP:4567/huya`
- 开启安全订阅后使用 `http://IP:4567/huya/TOKEN`

### 配置
![配置页面](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_config.png)

开启安全订阅，在订阅URL、点播API、播放API加上Token，防止被别人扫描。

开启"强制登录AList"后，连接WebDAV需要使用AList账号（在管理界面配置的用户名和密码）。

如果打开了挂载我的云盘功能，每次启动会消耗两次开放token请求。
如果使用AList官方认证URL，60分钟内只能请求10次，超过后需要等待60分钟后才能操作。

可以换IP绕开限制。或者更换开放token的认证URL。配置页面->高级设置 选择一个认证URL。

- https://api.xhofe.top/alist/ali_open/token
- https://api.nn.ci/alist/ali_open/token

如果nginx配置了SSL，需要在高级设置中打开`订阅域名支持HTTPS`开关。

#### 高级设置
可配置项：
- 安全订阅Token
- 阿里Token认证URL
- 订阅域名支持HTTPS
- 数据库备份与导出（`GET /api/settings/export`）
- 网盘帐号负载均衡
- 自动清理失效分享
- 调试日志
- 自定义User-Agent
- 搜索排除路径
- 临时分享过期时间（默认72小时）
- 分享验证间隔（默认4小时）
- 盘搜链接检测（可配置最大检测数量）
- 电报搜索超时时间
- 盘搜认证（用户名/密码）
- 盘搜插件过滤


### 索引
对于阿里云盘资源，建议使用文件数量少的路径，并限速，防止被封号。

![索引页面](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_index.png)

![索引模板](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_index_template.png)

#### 索引文件
路径前缀说明：
- `-` 开头：屏蔽搜索和刮削
- `+` 开头：屏蔽刮削，允许搜索
- `>` 开头：重置路径（清除之前的索引数据后重新索引）

下载索引文件修改后再上传。

#### 索引模板
支持设置定时任务，自动在指定时间重新构建索引。

系统会在每天22点和黄金时段（10、12、14、16、18-23点）自动执行已启用的定时索引模板。

#### 索引功能
- **增量索引**：在已有索引基础上追加，不删除已有数据
- **压缩索引**：生成ZIP压缩格式的索引文件
- **排除外部**：排除来自AList Provider的条目
- **自定义深度**：支持 `path:file:depth` 语法按路径设置不同索引深度

#### 索引与刮削
在电影数据列表页面对索引文件进行刮削，根据路径提取电影名称。如果无法正确识别名称，需要手动刮削。

索引文件修改：
路径#名称#豆瓣ID

比如：
1. 修正名称后刮削：
/电影/中国/F 封神：朝歌风云 [2023][4K]动作 战争 奇幻 古装[正式版]#封神第一部：朝歌风云

2. 提供豆瓣ID刮削：
/电影/中国/F 封神：朝歌风云 [2023][4K]动作 战争 奇幻 古装[正式版]##10604086

#### TMDB刮削
1. 申请TMDB账号，https://www.themoviedb.org/
2. 申请TMDB API key，https://developer.themoviedb.org/docs/getting-started
3. 配置页面 -> 高级设置 -> TMDB API Key -> 填写你的 API Key
4. 创建索引
5. TMDB电影数据列表，使用索引文件进行刮削
6. 失败的路径保存在 /opt/alist-tvbox/atv/tmdb_paths.txt

使用内置的API Key会限速，建议申请自己的API key。

### GitHub代理
需要通过GitHub下载分享数据和索引数据。

创建文件/opt/alist-tvbox/github_proxy.txt， 内容为GitHub代理地址，注意以/结尾。

比如`https://gh-proxy.net/`

### HTTP代理
创建文件/opt/alist-tvbox/proxy.txt， 内容为代理地址。

比如`http://192.168.0.1:8080`

比如`http://user:pass@proxy.example.com:8080`

比如`socks5://proxy.example.com:1080`

### 别名
把一些路径合并成一个路径。

![别名页面](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_alias.png)

### WebDAV
默认用户名：guest 密码：alist_tvbox

**注意**：如果在配置页面开启了"强制登录AList"，则需要使用AList账号（在管理界面配置的用户名和密码）来连接WebDAV。

![WebDAV](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/webdav.jpg)

4567端口代理了webdav请求。

### 设备管理
管理TvBox设备，支持扫码添加设备。

功能：
- 扫描局域网内的TvBox设备
- 推送内容到设备
- 同步观看历史
- 通过二维码配置TvBox订阅地址

### 观看历史
支持观看历史的记录和管理。

TvBox接口：`http://IP:4567/history/{token}`

管理接口：`http://IP:4567/api/history`

### 视频管理
对播放URL进行管理操作：
- 评分
- 重命名
- 移动
- 删除

管理接口：`http://IP:4567/api/videos`


### 电报搜索
不登陆默认使用网页搜索公开频道资源。

在订阅页面登陆电报或者配置远程搜索，可以搜索更多频道。
在播放页面配置频道列表。

如果在订阅页面不能登陆电报，在播放页面配置远程搜索地址 http://IP:7856 。

#### 电报搜索接口
- `/tg-search` - 电报搜索（Token可选）
- `/tgsc` - 电报搜索（使用tg-search API）
- `/tg-db` - 豆瓣数据库浏览（支持分类：热门电视剧、热门电影、国产剧、美剧、动漫、综艺、韩剧、日剧、推荐、Top250、实时热门、每周最佳，以及本地浏览和随机发现）
- `/tgs` - 电报订阅源

#### 盘搜
建议部署盘搜，不需要在订阅页面登陆电报，也不需要配置远程搜索。
```bash
docker run -d --name pansou -p 8888:8888 -v pansou-cache:/app/cache --restart=always ghcr.io/fish2018/pansou
```

盘搜支持链接检测功能，可以批量检查分享链接是否有效。

#### 部署电报搜索服务
1. 下载对应平台的文件解压
-  https://har01d.org/tgs-amd64.zip
-  https://har01d.org/tgs-arm64.zip
-  https://har01d.org/tgs-armv7.zip
2. 第一次直接启动： `./tgs-amd64`
3. 输入手机号和验证码，需要加国际区号86
4. 然后使用nohup后台运行： `nohup ./tgs-amd64 &`
5. 环境变量`TGS_PORT`，设置端口，默认为`7856`

### 自定义路径label
作用：TvBox首页和搜索显示，用来区分同一资源不同的路径。

在文件界面新建一个文件/data/label.txt
```text
🎞:/电影  #匹配以/电影 开头的路径
📺:/电视剧
🧸:/动漫
🎤:/综艺
🔬:/纪录片
🎶:/音乐
📖:/有声书
🧺:/整理中
🅿️:/每日更新/PikPak #顺序很重要
📅:/每日更新
🎓:/教育
🎸:/曲艺
⚽️:/体育
📮:/🈴我的阿里分享/Tacit0924 #顺序很重要
🈴:/🈴我的阿里分享
5️⃣:115  #路径包含115
🅿️:PikPak
📀:阿里云盘
🌞:夸克网盘
🎎:我的套娃
```

### 使用MySql数据库
独立服务版编辑配置文件/opt/atv/config/application-production.yaml

Docker版在数据目录创建config目录，创建文件application-production.yaml，
比如/opt/alist-tvbox/config/application-production.yaml。

application-production.yaml文件内容示例：
```yaml
spring:
   datasource:
      jdbc-url: jdbc:mysql://localhost:3306/alist_tvbox?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
      username: username
      password: password
      driver-class-name: com.mysql.cj.jdbc.Driver
   jpa:
      database-platform: org.hibernate.dialect.MySQL8Dialect
      hibernate:
         ddl-auto: update
      show-sql: false
```

独立服务版编辑配置文件/opt/atv/alist/data/config.json
Docker版挂载/opt/alist/data/config.json

AList配置参考[alist-mysql.json](../config/alist-mysql.json)

### h2数据备份与恢复
每天6点自动备份数据库，保存在/opt/alist-tvbox/backup/目录。

如何恢复？
1. 将保存的备份文件复制到/opt/alist-tvbox/database.zip
2. 删除文件/opt/alist-tvbox/atv.mv.db和/opt/alist-tvbox/atv.trace.db
3. 重启docker容器或者重新运行安装脚本

也可以在配置页面导出数据库备份。

### 静态文件
将自己的文件test.json放在/www/tvbox/目录，可以通过 http://IP:4567/tvbox/test.json 访问。

http://IP:4567/tvbox/ -> /www/tvbox/

http://IP:4567/files/ -> /www/files/

http://IP:4567/cat/ -> /www/cat/

http://IP:4567/pg/ -> /www/pg/

http://IP:4567/zx/ -> /www/zx/


### 其它
不再生效的文件可以保留，以后删除数据库后可以恢复。

guestpass.txt和guestlogin.txt第一次启动时加载，以后不再生效，请在界面配置。

show_my_ali.txt第一次启动时加载，以后不再生效，请在界面配置是否加载阿里云盘。

docker_address.txt不再生效，使用订阅链接会自动识别。

alist_list.txt第一次启动时加载，以后不再生效，请在界面添加站点。

proxy.txt、tv.txt、my.json、iptv.m3u还是生效的，可以在文件页面编辑。

本项目不会使用alist.min.js。

## 常见问题

1. **AList出现错误 failed get objs: failed to list objs: driver not init**

   在管理界面->资源页面->失败资源 查看具体原因

2. **AList出现错误 failed link: failed get link: The resource drive has exceeded the limit. File size exceeded drive capacity**

   阿里网盘空间满了，清理一下文件。

3. **AList出现错误 failed link: failed get link: No permission to access resource File**

   token失效，重启应用。AList日志检查阿里token账号昵称和开放token账号昵称是否一致。

4. **管理界面没有账号页面**
   
   刷新一下网页。

5. **夸克分享/UC分享无法播放**
   
   需要在帐号页面添加夸克网盘/UC网盘cookie。

6. **阿里转存115限制**
   
   阿里转存115有文件大小限制，并且115必须有对应文件存在。115需要会员。115删除码在115应用设置。

7. **迅雷云盘登录问题**
   
   迅雷云盘，用户名 +86后面要加一个空格，比如+86 12345678900 在资源页面 -> 失败资源 -> 点击 重新加载。
   多试几次，状态列出现链接后，打开链接验证。然后再次点击重新加载。

8. **纯净版AList管理**
   
   纯净版可以进AList后台管理页面。管理员用户名是atv，密码在高级设置里面查看。

9. **重置用户名和密码**
   
   创建文件/opt/alist-tvbox/atv/cmd.sql，写入下面的内容。重启应用，恢复默认的admin密码。
   ```sql
   UPDATE users SET username='admin', password='$2a$10$90MH0QCl098tffOA3ZBDwu0pm24xsVyJeQ41Tvj7N5bXspaqg8b2m' WHERE id=1;
   ```

10. **网盘文件更新不显示**
    
    网盘添加了文件，在AList看不到。因为AList有缓存，默认30分钟。等待缓存过期，或者重启AList。

11. **离线下载配置**
    
    离线下载需要在账号页面点击"配置"，先设置目标网盘账号。目前支持115云盘、光鸭云盘和迅雷云盘。

12. **光鸭云盘登录**
    
    光鸭云盘需要在网盘帐号页面添加，支持OAuth设备码授权方式登录。

13. **电报豆瓣数据库**
    
    电报豆瓣数据库（/tg-db）需要先在电报频道搜索中添加频道数据，才能浏览豆瓣分类内容。

## 项目链接

- GitHub: https://github.com/power721/alist-tvbox
- Docker Hub: https://hub.docker.com/r/haroldli/xiaoya-tvbox
- 作者主页: https://har01d.cn/

## 许可证

本项目基于开源协议发布，仅供学习交流使用。

