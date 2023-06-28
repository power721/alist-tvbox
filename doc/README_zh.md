# AList-TvBox
AList代理，支持xioaya版AList界面管理。
## 功能
- 管理界面
- 海报墙
- 多个AList站点
- 多个阿里云盘账号
- 挂载我的云盘
- 自动签到
- 自动刷新阿里Token
- 自定义TvBox配置
- 安全订阅配置
- TvBox配置聚合
- 添加阿里云盘分享
- 管理AList服务
- 小雅配置文件管理

## 安装
### 一键安装
#### 小雅集成版
（不需要再安装小雅版Docker）
```bash
curl -s https://d.har01d.cn/update_xiaoya.sh | sudo bash
```
使用其它配置目录：
```bash
curl -s https://d.har01d.cn/update_xiaoya.sh | sudo bash -s /home/user/atv
```
使用其它端口：
```bash
curl -s https://d.har01d.cn/update_xiaoya.sh | sudo bash -s /etc/xiaoya 8080
```
OpenWrt去掉sudo，或者已经是root账号：
```bash
curl -s https://d.har01d.cn/update_xiaoya.sh | bash
```

#### 独立版
```bash
curl -s https://d.har01d.cn/update_new.sh | bash
```
独立版请使用小雅搜索索引文件： http://d.har01d.cn/index.video.zip

#### NAS
对于群辉等NAS系统，请挂载Docker的/data目录到群辉文件系统。

## 管理
打开管理网页：http://your-ip:5678/ 

默认用户名：admin 密码：admin

点击右上角菜单，进入用户界面修改用户名和密码。

### 站点
![站点列表](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sites.png)

小雅版默认添加了站点：`http://localhost`，如果配置有域名，自行修改地址。

访问AList，请加端口5244，http://your-ip:5244/

自己可以添加三方站点，取代了xiaoya的套娃。会自动识别版本，如果不能正确识别，请手动配置版本。

![添加站点](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_site_config.png)

如果AList开启了强制登录，xiaoya版会自动填写认证token。

对于独立版需要手动获取认证Token，执行命令：
```bash
docker exec -i xiaoya sqlite3 /opt/alist/data/data.db <<EOF
select value from x_setting_items where key = "token"; 
EOF
```

![站点数据](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_site_data.png)

### 账号
![账号列表](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_account.png)

第一次启动会自动读取/data/mytoken.txt,/data/myopentoken.txt,/data/temp_transfer_folder_id.txt里面的内容，以后这些文件不再生效。

![账号详情](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_account_detail.png)

#### 转存文件夹ID
在阿里网盘网页版上创建一个转存目录，比如“temp”.

然后点击目录，浏览器显示的 URL
https://www.aliyundrive.com/drive/folder/640xxxxxxxxxxxxxxxxxxxca8a 最后一串就是。

### 订阅
![订阅列表](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sub.png)

![添加订阅](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sub_config.png)

添加订阅支持多个URL，用逗号隔开。定制部分基本和TvBox的配置json一致，添加了站点白名单`sites-whitelist`和黑名单`sites-blacklist`。

定制属于高级功能，不懂TvBox配置格式不要轻易改动。

站点`key`是必须的，其它字段可选。对于lives，rules，parses，doh类型，`name`字段是必须的。

站点名称可以加前缀，通过订阅URL前面加前缀，使用`@`分割。比如：`饭@http://饭太硬.top/tv,菜@https://tvbox.cainisi.cf`

![订阅预览](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sub_data.png)

### 资源
第一次启动会自动读取/data/alishare_list.txt文件里面的分享内容，并保存到数据库，以后这个文件就不再生效。

可以在界面批量导入文件里面的分享内容，批量删除分享。

![分享列表](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_shares.png)

### 配置
![配置页面](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_config.png)

开启安全订阅，在订阅URL、点播API、播放API加上Token，防止被别人扫描。

强制登录AList后，连接webdav需要使用下面的用户名和密码。

阿里token和开放token每天会刷新，时间和自动签到时间一致。即使没有开启自动签到，也会刷新。

### 其它
不再生效的文件可以保留，以后删除数据库后可以恢复。

guestpass.txt和guestlogin.txt第一次启动时加载，以后不再生效，请在界面配置。

show_my_ali.txt第一次启动时加载，以后不再生效，请在界面配置是否加载阿里云盘。

docker_address.txt不再生效，请使用订阅API。

alist_list.txt第一次启动时加载，以后不再生效，请在界面添加站点。

proxy.txt、tv.txt、pikpak.txt、my.json、iptv.m3u还是生效的，可以在文件页面编辑。
