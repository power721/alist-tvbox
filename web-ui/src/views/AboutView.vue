<template>
  <div class="about">
    <p>AList proxy server for TvBox, support playlist and search.</p>
    <p>
      源代码：
      <a href="https://github.com/power721/alist-tvbox" target="_blank"
        >https://github.com/power721/alist-tvbox</a
      >
    </p>
    <p>
      中文文档：<a href="https://har01d.cn/#/notes/alist-tvbox" target="_blank"
        >https://har01d.cn/#/notes/alist-tvbox</a
      >
    </p>
    <p>
      <a href="https://github.com/power721/alist-tvbox/blob/master/doc/README_zh.md" target="_blank"
        >https://github.com/power721/alist-tvbox/blob/master/doc/README_zh.md</a
      >
    </p>
    <p>
      OpenList：
      <a href="https://doc.oplist.org/guide/drivers/common" target="_blank"
        >https://doc.oplist.org/</a
      >
    </p>
    <p>
      Docker：
      <a href="https://hub.docker.com/r/haroldli/xiaoya-tvbox" target="_blank"
        >https://hub.docker.com/r/haroldli/xiaoya-tvbox</a
      >
    </p>
    <p>
      Telegram：
      <a href="https://t.me/+Vlgs24ZZxiM1Yzc1" target="_blank">https://t.me/alist_tvbox_group</a>
    </p>
    <p>
      手动部署Docker版：
      <code
        >docker run -d -p 4567:4567 -p 5344:80 -e ALIST_PORT=5344 -v /etc/xiaoya:/data
        --restart=always --name=xiaoya-tvbox haroldli/xiaoya-tvbox</code
      >
    </p>
    <p>
      手动部署Docker版：
      <code
        >docker run -d -p 4567:4567 -p 5344:5244 -e ALIST_PORT=5344 -v /etc/atv:/data
        --restart=always --name=alist-tvbox haroldli/alist-tvbox</code
      >
    </p>
    <p>
      一键部署(系统服务版)：
      <code
        >curl -fsSL http://d.har01d.cn/install-service.sh -o install-atv.sh && sudo bash
        ./install-atv.sh</code
      ><br />
    </p>
    <p>
      一键部署(系统服务版)：
      <code
        >wget -q http://d.har01d.cn/install-service.sh -O install-atv.sh && sudo bash
        ./install-atv.sh</code
      ><br />
    </p>
    <p>
      一键部署（Docker版）：
      <code>sudo bash -c "$(curl -fsSL http://d.har01d.cn/alist-tvbox.sh)"</code><br />
    </p>
    <!--    <p>-->
    <!--      一键部署(电报监控版)：-->
    <!--      <code>sudo bash -c "$(curl -fsSL http://d.har01d.cn/update_new.sh)" -s -t tg</code><br>-->
    <!--    </p>-->
    <!--    <p>-->
    <!--      一键部署(小雅版)：-->
    <!--      <code>sudo bash -c "$(curl -fsSL http://d.har01d.cn/update_xiaoya.sh)"</code><br>-->
    <!--    </p>-->
    <!--    <p>-->
    <!--      一键部署(内存优化版)：-->
    <!--      <code>sudo bash -c "$(curl -fsSL http://d.har01d.cn/update_new.sh)" -s -t native</code><br>-->
    <!--      <code>sudo bash -c "$(curl -fsSL http://d.har01d.cn/update_native.sh)"</code><br>-->
    <!--      <code>sudo bash -c "$(curl -fsSL http://d.har01d.cn/update_native_host.sh)"</code><br>-->
    <!--    </p>-->
    <!--    <p>-->
    <!--      一键部署(host网络模式)：-->
    <!--      <code>sudo bash -c "$(curl -fsSL http://d.har01d.cn/update_hostmode.sh)"</code><br>-->
    <!--    </p>-->
    <!--    <p>-->
    <!--      一键部署(开发版)：-->
    <!--      <code>sudo bash -c "$(curl -fsSL http://d.har01d.cn/update_xiaoya.sh)" -s -t dev</code><br>-->
    <!--    </p>-->
    <!--    <p>-->
    <!--      一键部署(NAS)：-->
    <!--      <code>sudo bash -c "$(curl -fsSL http://d.har01d.cn/update_xiaoya.sh)" -s /volume2/docker/xiaoya</code>-->
    <!--    </p>-->
  </div>
</template>
<script setup lang="ts"></script>
