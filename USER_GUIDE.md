# CPU 100% 问题诊断指南（用户版）

## 问题现象

打开订阅管理页面后，容器 CPU 占用 100%，页面卡死无响应。

## 当问题发生时的操作步骤

### 第1步：收集诊断信息（重要！）

在问题发生时，**不要立即重启容器**，先收集诊断信息：

#### 方法1：访问健康检查页面

打开浏览器新标签页，访问以下地址（替换为你的实际地址）：

```
http://你的IP地址:5244/api/health/metrics
```

例如：`http://192.168.1.100:5244/api/health/metrics`

**复制整个页面内容，保存到文本文件。**

#### 方法2：访问线程详情页面

```
http://你的IP地址:5244/api/health/threads
```

**同样复制页面内容，保存到文本文件。**

### 第2步：导出容器日志

在服务器上执行以下命令（如果有SSH权限）：

```bash
# 导出最近 200 行日志
docker logs alist-tvbox --tail=200 > alist-tvbox-logs.txt

# 或者导出全部日志
docker logs alist-tvbox > alist-tvbox-full-logs.txt
```

如果无法SSH，可以使用 Docker 管理工具（如 Portainer）导出日志。

### 第3步：重启容器恢复服务

```bash
docker restart alist-tvbox
```

### 第4步：提交问题报告

将以下信息发给开发者：

1. `/api/health/metrics` 页面内容
2. `/api/health/threads` 页面内容
3. 容器日志文件
4. 问题发生时正在做什么操作

---

## 日常预防措施

### 1. 定期重启容器

为避免问题累积，建议每周重启一次容器：

```bash
docker restart alist-tvbox
```

或者使用 cron 定时任务：

```bash
# 每周日凌晨 3 点重启
0 3 * * 0 docker restart alist-tvbox
```

### 2. 避免同时打开多个订阅页面

不要在多个浏览器标签页同时打开订阅管理页面。

### 3. 升级到最新版本

确保使用最新版本的镜像：

```bash
docker pull haroldli/alist-tvbox:latest
docker-compose up -d
```

---

## 新版本改进说明

最新版本添加了以下防护措施：

### 1. 健康检查端点

- `/api/health/metrics` - 查看系统运行指标
- `/api/health/threads` - 查看线程状态和 CPU 占用

### 2. API 超时保护

- 订阅列表查询：10秒超时
- 设备列表查询：5秒超时
- 超时后不会卡死，返回空列表

### 3. 慢请求日志

所有耗时超过 3 秒的 API 请求都会记录到日志中：

```
⚠️ 慢请求: GET /api/subscriptions 耗时 5234ms
```

### 4. 前端超时保护

前端请求设置 15 秒超时，超时后自动提示用户刷新页面。

---

## 常见问题

### Q1: 如何查看容器资源使用情况？

```bash
# 查看实时资源使用
docker stats alist-tvbox

# 持续监控（按 Ctrl+C 退出）
docker stats alist-tvbox --no-stream=false
```

### Q2: 如何限制容器资源？

修改 `docker-compose.yml`：

```yaml
services:
  alist-tvbox:
    deploy:
      resources:
        limits:
          cpus: '2.0'    # 限制最多使用 2 个 CPU
          memory: 2G     # 限制最多使用 2GB 内存
```

然后重新部署：

```bash
docker-compose up -d
```

### Q3: 健康检查页面打不开怎么办？

如果 `/api/health/metrics` 无法访问，说明容器可能完全卡死。此时只能：

1. 强制重启容器：`docker restart -t 0 alist-tvbox`
2. 查看容器是否还在运行：`docker ps | grep alist-tvbox`
3. 如果容器已停止，查看退出原因：`docker logs alist-tvbox --tail=50`

### Q4: 日志文件太大怎么办？

只导出最近的日志：

```bash
# 最近 500 行
docker logs alist-tvbox --tail=500 > logs.txt

# 最近 1 小时的日志
docker logs alist-tvbox --since 1h > logs.txt

# 指定时间段的日志
docker logs alist-tvbox --since "2024-01-01T00:00:00" --until "2024-01-02T00:00:00" > logs.txt
```

---

## 紧急恢复命令

如果容器完全无响应，使用以下命令强制重启：

```bash
# 方法1：强制重启（0秒超时）
docker restart -t 0 alist-tvbox

# 方法2：停止后启动
docker stop alist-tvbox
docker start alist-tvbox

# 方法3：完全重建（会丢失未持久化的数据）
docker-compose down
docker-compose up -d
```

---

## 联系开发者

提交 Issue 时请附上：

1. 健康检查页面内容
2. 容器日志
3. Docker 版本：`docker --version`
4. 系统信息：`uname -a`
5. 容器配置：`docker inspect alist-tvbox`

GitHub Issues: https://github.com/power721/alist-tvbox/issues
