# 上游与发行说明

独立维护平台从AList-TVBox项目工作区拆出；上游项目为power721/alist-tvbox，原工程基线提交为 `7532bb69a1d9399d8b08520da0d794b31aad1a27`。本候选保留所选源码中的原有署名，不将其宣称为全部独立原创。

当前本地工程未找到可直接随独立候选交付的根LICENSE/COPYING文件。**此事实不是对上游授权状态的法律判断。公开分发前仍需核实所含上游文件的许可与再分发条件，并补齐所需许可材料。** 本文不重新授权上游代码，也不擅自为整个项目指定MIT、GPL或其他许可证。

Java依赖由独立POM及固定父版本声明；Vue/Element Plus依赖由Web的package-lock.json固定；可选逆向组件依赖由其package-lock.json固定，署名和采用边界见 `plugin-maintenance/reverse-engine/THIRD_PARTY.md`。安装的第三方包保留自身许可文件。

本包是本地源码/JAR发行候选，不是公开发布或许可审核完成的声明。默认不包含第三方node_modules、浏览器二进制、生产镜像、连接秘密或真实站点样本。
