# Release Notes - 1.35.0

## 新增

- Spider 插件支持自声明配置结构：插件可通过 `PLUGIN_CONFIG_SCHEMA` 声明自身需要的配置项（站点、账号、密码、Cookie 等），订阅页自动渲染配置表单，免手写 extend JSON；加密 `.txt` 也可通过明文 `//@config-schema:` 头声明配置结构
- 播放配置新增独立「盘检地址」与「盘检超时」：盘检（网盘链接有效性检测）不再硬编码到 PanSou，改为按优先级回退选择检测后端——盘检地址（PanCheck）> TG-Search > PanSou；盘检超时（ms）可选，仅对 TG-Search 生效
