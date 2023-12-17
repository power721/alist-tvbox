# AList-TvBox
AList代理，支持xiaoya版AList界面管理。

## 简明教程
1. 准备工作：安装Docker
2. 安装AList-TvBox：
    ```bash
    sudo bash -c "$(curl -fsSL https://d.har01d.cn/update_xiaoya.sh)"
    ```
3. 打开管理界面：http://your-ip:4567/#/accounts
   默认用户名：admin 密码：admin
4. 获取并填写阿里token、开放token
5. 将订阅地址[http://your-ip:4567/sub/0](http://your-ip:4567/sub/0) 输入到TvBox配置

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
- 添加PikPak分享
- 支持BiliBili
- 管理AList服务
- 小雅配置文件管理
- 构建索引
- 在线日志

## 安装
### 一键安装
#### 小雅集成版
不需要再安装小雅版Docker。

如果找不到bash就替换为sh。

如果找不到sudo，就用root账号登录，去掉sudo后运行。

```bash
sudo bash -c "$(curl -fsSL https://d.har01d.cn/update_xiaoya.sh)"
```
使用其它配置目录：
```bash
sudo bash -c "$(curl -fsSL https://d.har01d.cn/update_xiaoya.sh)" -s /home/user/atv
```
挂载本地目录：
```bash
sudo bash -c "$(curl -fsSL https://d.har01d.cn/update_xiaoya.sh)" -v /home/user/Videos:/video
```
使用其它端口：

- 第一个参数是挂载的数据目录，默认是/etc/xiaoya。
- 第二个参数是管理界面端口，默认是4567。
- 第三个参数是小雅AList端口，默认是5344。
```bash
sudo bash -c "$(curl -fsSL https://d.har01d.cn/update_xiaoya.sh)" -s /home/alist 8080
sudo bash -c "$(curl -fsSL https://d.har01d.cn/update_xiaoya.sh)" -s /home/alist 8080 5544
```
OpenWrt去掉sudo，或者已经是root账号：
```bash
bash -c "$(curl -fsSL https://d.har01d.cn/update_xiaoya.sh)"
```

如果没有安装curl:
```bash
wget https://d.har01d.cn/update_xiaoya.sh; sh ./update_xiaoya.sh
```

#### 内存优化版
目前仅支持Linux x86_64平台。
```bash
wget https://d.har01d.cn/update_xiaoya.sh -O update_xiaoya.sh && sh ./update_xiaoya.sh -t native
```

#### host网络模式
使用host网络模式运行：
```bash
sudo bash -c "$(curl -fsSL https://d.har01d.cn/update_hostmode.sh)"
```
使用的端口：

6789 - nginx

5233 - httpd

5234 - AList

4567 - 管理应用

#### 纯净版
没有内置数据。
```bash
sudo bash -c "$(curl -fsSL https://d.har01d.cn/update_new.sh)"
```

#### NAS
对于群辉等NAS系统，请挂载Docker的/data目录到群辉文件系统，否则数据不会保留。

### 海报展示
#### 浏览目录
![浏览目录](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/poster1.jpg)
#### 搜索界面
![搜索界面](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/poster2.jpg)
#### 播放界面
![播放界面](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/poster3.jpg)

## 管理
打开管理网页：http://your-ip:4567/ 

默认用户名：admin 密码：admin

点击右上角菜单，进入用户界面修改用户名和密码。

### 站点
![站点列表](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sites.png)

默认添加了站点：`http://localhost`，如果配置有域名，自行修改地址。

为什么是`http://localhost`？ 因为小雅用80端口代理了容器内的AList 5244端口。
管理程序运行在同一个容器内，能够直接访问80端口。

访问AList，请加端口，http://your-ip:5344/ 。使用Docker映射的端口，默认是5344.

自己可以添加三方站点，功能与xiaoya的套娃类似。会自动识别版本，如果不能正确识别，请手动配置版本。

![添加站点](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_site_config.png)

如果AList开启了强制登录，会自动填写认证token。

![站点数据](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_site_data.png)

### 账号
![账号列表](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_account.png)

第一次启动会自动读取/data/mytoken.txt,/data/myopentoken.txt里面的内容，以后这些文件不再生效。
自动创建转存文件夹，不需要再填写转存文件夹ID。

修改主账号后需要重启AList服务。

![账号详情](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_account_detail.png)

### 订阅
tvbox/my.json和juhe.json不能在TvBox直接使用，请使用订阅地址！

![订阅列表](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sub.png)

![添加订阅](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sub_config.png)

添加订阅支持多个URL，用逗号隔开。定制部分基本和TvBox的配置json一致，添加了站点白名单`sites-whitelist`和黑名单`sites-blacklist`。

定制属于高级功能，不懂TvBox配置格式不要轻易改动。

站点`key`是必须的，其它字段可选。对于lives，rules，parses，doh类型，`name`字段是必须的。

站点名称可以加前缀，通过订阅URL前面加前缀，使用`@`分割。比如：`饭@http://饭太硬.top/tv,菜@https://tvbox.cainisi.cf`

替换功能：

在配置页面->高级设置里面找到阿里Token地址，然后在订阅-定制里面自替换token。

```json
{
    "sites-blacklist": ["说明1","说明2", "说明3","说明4","公告", "ext_live_protocol", "cc"],
    "sites": [
     
    ]
}
```

![订阅预览](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_sub_data.png)

### 资源
第一次启动会自动读取/data/alishare_list.txt文件里面的分享内容，并保存到数据库，以后这个文件就不再生效。

可以在界面批量导入文件里面的分享内容，批量删除分享。

添加资源如果路径以/开头就会创建在根目录下。否则在/🈴我的阿里分享/下面。

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
目前只有影视TV支持高清高速的DASH格式视频。

拖动行可以改变顺序，需要点击保存按钮才能生效。

打开、关闭显示开关后，需要点击保存按钮才能生效。

![BiliBili](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_bilibili.png)

登录后才能使用，TvBox第三个站源。

![扫码登录](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_bilibili_login.png)

或者使用已有的cookie登录。

打开上报播放记录，B站才能看到播放记录。

![配置](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_bilibili_config.png)

添加搜索关键词作为一级分类：

![搜索](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_bilibili_search.png)

添加频道作为一级分类：（目前不可用）

![频道](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_bilibili_channel.png)

### 配置
![配置页面](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_config.png)

开启安全订阅，在订阅URL、点播API、播放API加上Token，防止被别人扫描。

强制登录AList后，连接webdav需要使用下面的用户名和密码。

如果打开了挂载我的云盘功能，每次启动会消耗两次开放token请求。60分钟内只能请求10次，超过后需要等待60分钟后才能操作。

可以换IP绕开限制。或者更换开放token的认证URL。配置页面->高级设置 选择一个认证URL。

- https://api-cf.nn.ci/alist/ali_open/token
- https://api.xhofe.top/alist/ali_open/token
- https://api.nn.ci/alist/ali_open/token

如果nginx配置了SSL，需要在高级设置中打开`订阅域名支持HTTPS`开关。

### 索引
对于阿里云盘资源，建议使用文件数量少的路径，并限速，防止被封号。

![索引页面](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_index.png)

![索引模板](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_index_template.png)

#### 索引文件
路径开头加上-：表示此路径屏蔽搜索和刮削。

路径开头加上+：表示此路径屏蔽刮削，允许搜索。

下载索引文件修改后再上传。

#### 索引与刮削
在电影数据列表页面对索引文件进行刮削，根据路径提取电影名称。如果无法正确识别名称，需要手动刮削。

索引文件修改：
路径#名称#豆瓣ID

比如：
1. 修正名称后刮削：
/电影/中国/F 封神：朝歌风云 [2023][4K]动作 战争 奇幻 古装[正式版]#封神第一部：朝歌风云

2. 提供豆瓣ID刮削：
/电影/中国/F 封神：朝歌风云 [2023][4K]动作 战争 奇幻 古装[正式版]##10604086

### 别名
把一些路径合并成一个路径。

![别名页面](https://raw.githubusercontent.com/power721/alist-tvbox/master/doc/atv_alias.png)

### 其它
不再生效的文件可以保留，以后删除数据库后可以恢复。

guestpass.txt和guestlogin.txt第一次启动时加载，以后不再生效，请在界面配置。

show_my_ali.txt第一次启动时加载，以后不再生效，请在界面配置是否加载阿里云盘。

docker_address.txt不再生效，使用订阅链接会自动识别。

alist_list.txt第一次启动时加载，以后不再生效，请在界面添加站点。

proxy.txt、tv.txt、my.json、iptv.m3u还是生效的，可以在文件页面编辑。

本项目不会使用alist.min.js。
