# alist-tvbox
AList代理，支持xioaya版AList界面管理。
## 安装
### 一键安装
```bash
curl -s http://d.har01d.cn/update_xiaoya.sh | sudo bash
```
## 管理
打开管理网页：http://your-ip:5678/ 

默认用户名：admin 密码：admin

点击右上角菜单，进入用户界面修改用户名和密码。

### 站点
![站点列表](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sites.png)

小雅版默认添加了站点：`http://localhost`，如果配置有域名，自行修改地址。

自己可以添加三方站点，取代了xiaoya的套娃。会自动识别版本，如果不能正确识别，请手动配置版本。

![添加站点](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_site_config.png)

如果AList开启了强制登录，xiaoya版会自动填写认证token。

对于独立版需要手动获取Token，执行命令：
```bash
docker exec -i xiaoya sqlite3 /opt/alist/data/data.db <<EOF
select value from x_setting_items where key = "token"; 
EOF
```

![站点数据](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_site_data.png)

### 订阅
![订阅列表](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sub.png)

![添加订阅](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sub_config.png)

添加订阅支持多个URL，用逗号隔开。定制部分基本和TvBox的配置json一致，添加了站点白名单`sites-whitelist`和黑名单`sites-blacklist`。

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

第一次启动会自动读取/data/mytoken.txt,/data/myopentoken.txt,/data/temp_transfer_folder_id.txt里面的内容，以后这些文件不再生效。

阿里token和开放token每天会刷新，时间和自动签到时间一致。即使没有开启自动签到，也会刷新。

### 其它
不再生效的文件都可以删除。

guestpass.txt和guestlogin.txt不再生效，请在界面配置用户名和密码。

show_my_ali.txt不再生效，请在界面配置是否加载阿里云盘。

docker_address.txt不再生效，请使用订阅API。

alist_list.txt不再生效，请在界面添加站点。

proxy.txt还是生效的。
