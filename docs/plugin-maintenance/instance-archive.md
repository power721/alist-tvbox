# 实例冷归档与恢复

`plugin-maintenance/scripts/instance_archive.py`在原H2冷归档外统一保存当前应用JAR、
所选数据目录、本地插件/静态文件及AList SQLite快照。适用于独立工作台与AT内置模式；
不是在线备份，不自动安装容器环境、拉镜像、启动应用或重新执行维护任务。
生产实例仍只读；先对自己持有且已经停机的隔离实例使用。

需要离线携带容器镜像时，使用[离线部署包](deployment-archive.md)封装本工具生成的可信实例ZIP及其实际镜像；原实例归档格式和恢复行为保持，不因这个附加工具重做既有验收。

## 配置

离线实例描述使用JSON，路径均为本机路径。数据根只声明一次；
容器的嵌套挂载通过`mounts`引用其子目录，不复制出互不相干的两份数据。

```json
{
  "schemaVersion": 1,
  "deployment": "at-embedded",
  "roots": {
    "data": "/owned-instance/data",
    "alist": "/owned-instance/alist-data"
  },
  "database": "data/atv.mv.db",
  "sqlite": ["alist/data.db"],
  "mounts": {
    "/data": "data",
    "/www/static": "data/www-static",
    "/opt/alist/data": "alist"
  },
  "imageId": "sha256:<实际镜像ID的64位小写摘要>",
  "credentialReferences": ["MAINTENANCE_MODEL_API_KEY"]
}
```

独立模式用`standalone`及`data/maintenance.mv.db`，没有SQLite时填空数组。
`imageId`可省略用于直接JAR部署；容器实例应记录实际镜像ID，并另行保留该镜像。
不会读取`credentialReferences`对应环境变量的值或把它们导出，恢复后重新绑定。
数据目录里的既有配置、数据库、日志仍可能包含凭据和私有内容；
**整个归档未加密，必须私下保存，不得进入Git或上游审阅包。**

## 操作

源实例所有写入者必须已停止；H2复用既有独占冷备份，不退回复制活动数据库。
H2冷备份需要打开可写连接，不能把待备份的数据根挂成只读。若要求原副本完全不变，
先复制已经停机的实例并核对文件字节，再只对该可写副本执行冷备份。
数据库密码沿用原工具环境变量：内置模式`AT_DB_PASSWORD`，独立模式
`MAINTENANCE_DB_PASSWORD`；由管理员提供该实例已有密码，不改密码或猜测生产配置。
通过容器包装Java命令时，也必须将已绑定的密码环境变量传入该临时容器；
仅在外层Python进程设置不会自动传到容器中，不要把密码值写入命令参数或回执。
SQLite使用标准备份API，包含WAL中已提交的数据，恢复包不携带旧的WAL/SHM文件。
目录内保存普通文件和空目录，不跟随符号链接或复制设备/socket；
这些内容需作为明确的数据根或外部运行前置单独提供。

```powershell
py -3.14 -X utf8 plugin-maintenance/scripts/instance_archive.py backup `
  --config D:\owned-lab\instance.json --output D:\private-backups\instance.zip `
  --java 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe' `
  --jar plugin-maintenance/at-embedded/target/alist-tvbox-1.0.jar
```

保留成功回执中的`archiveSha256`，不能把陌生ZIP自己计算的摘要当可信来源。

```powershell
py -3.14 -X utf8 plugin-maintenance/scripts/instance_archive.py inspect `
  --archive D:\private-backups\instance.zip --trusted-sha256 <自己保留的摘要>
py -3.14 -X utf8 plugin-maintenance/scripts/instance_archive.py restore `
  --archive D:\private-backups\instance.zip --output D:\owned-restored `
  --trusted-sha256 <自己保留的摘要> `
  --java 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
```

`inspect`只检查归档字节，不打开H2或启动运行包。`restore`会运行归档中自己备份的
JAR内原冷恢复入口校验H2；必须只接受自己可信的归档。也可显式提供`--jar`，
工具要求它与归档运行包完全同SHA，不能用不同运行包冒充原恢复环境。

成功目标包含`state/<各数据根>`、`runtime/application.jar`、可再次使用的
`runtime/instance_archive.py`、新的`instance.json`和`recovery-manifest.json`。
原数据库文件名、所有表/预算/历史记录、本地文件字节、SQLite表行数与挂载关系保留；
不覆盖现存目标，不修改原实例配置或密钥。

恢复后先隔离启动，关闭模型、巡检、外部执行和自动递交，核对实际镜像、
运行资源、挂载及快照后的外部操作/预算，再决定启用。历史成功和归属记录
只被保留，不授予新的外部操作权限，不声称原主机容器已清理或发布已回滚。
Java、Python及容器基础镜像属于明确的运行前置，不包含在ZIP里。

## 当前实证

2026-11-13，本机9个唯一检查最终通过；NAS停机隔离样例
`run-20261113-418c90ad9077`已完成归档、恢复并启动完整AT。
H2 72表、SQLite 9表、1插件/5过滤器、实际运行JAR和静态挂载恢复，
ADMIN登录、独立管理页、AList ping、T4过滤结果及运行身份通过。
实验容器已移除，生产身份未变，没有执行外站源或调用模型。
权威`recovery/instance-archive-20261113/nas-verification.json`。

独立比较确认原实例到归档的凭据行和配置字节相同。宿主启动后，
`x_users`中两条宿主重建账号的`salt`变化，id/用户名/密码字段未变；
不把启动后的全部数据库字节一致作为冷恢复条件，也不把这些字段一致
当作AList登录语义已验证。原失败结果保留，没有修改或重置凭据。
生产恢复、任意挂载布局、跨版本迁移、AList凭据登录和长期观察仍未认证。

### 原凭据登录补验（2026-11-13）

上述“AList凭据登录未认证”已由本次定向验收关闭，仅针对该可信归档的既有账号。
run`run-20261113-f0860d01d613`在既有恢复目录的独立副本启动同镜像、同JAR，
启动前读取已有登录配置，不重置密码。配置的前台账号ID3与宿主服务账号ID4
均通过真实`/api/auth/login`和认证后的`/api/me`身份核对；
服务账号读取管理设置通过，匿名访问被拒绝。
两个账号启动后salt重建，但原登录配置、用户名和密码字段保持且原凭据可用。
重建由`AccountService.setup/addAdminUser/enableLogin`的既有启动同步拥有，
本样例不需要修改认证或冷恢复代码。

权威`recovery/alist-restore-login-20261113/run-20261113-f0860d01d613/resume/verification.json`。
原归档SHA`08c4af17db4d2321a4e702e7cf70fc9e3a5b17eddfd3742fcf201f4efb9bbd4f`、
原恢复文件和生产身份保持；容器退出0并移除。首次助手复制目录属主错误的失败
独立保留，修正后只续此登录补验，没有重跑归档全套、爬虫/模型或构建产品。
生产恢复、任意账号/布局、跨版本和长期观察在本次登录补验时仍未认证。

### 可信归档旧库升到 0.73（2026-11-15）

跨版本缺项现已仅针对本归档的V61→V70关闭。新隔离副本run-20261115-6c8698877589
使用现成0.73内置JAR，原ADMIN、两版维护源码及静态历史、独立页/资源、导出和AList ping保持。
25张表既有列/行完全一致；设置表45/46行一致，唯一alist_start_time为正常启动更新时间。
原严格失败和只读比较失败保留，最终按明确运行字段例外接受，不声称数据库字节全相同。
原归档/原恢复文件和生产保持，五容器移除，无新镜像或产品构建。
详见[内置升版证据](embedded-upgrade-073-2026-11-15.md)，权威外层
`recovery/embedded-upgrade-073-20261115/final-summary.json`；已执行操作全部终态不重放。
生产升级/恢复、任意账号及布局、整机恢复和长期观察仍未认证。
