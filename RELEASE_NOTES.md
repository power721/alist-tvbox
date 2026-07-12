# Release Notes - 1.21.1

## 修复

- 修复电报视频详情：部分分享链接以 URL 编码形式（`%2Fv%2F`）传入时无法正确解析，现在会自动解码后正常播放。
- 修复 ATVP 站点解析：修正 `etree.HTML` 在部分运行环境（Chaquopy/libxml2）下对含编码声明的字符串误报 "encoding not supported" 的兜底逻辑，改用 UTF-8 字节重试，避免二次失败。
- 同步更新本地包（spring.jar）。
