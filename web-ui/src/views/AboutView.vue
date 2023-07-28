<template>
  <div class="about">
    <p>AList proxy server for TvBox, support playlist and search.</p>
    <p>
      源代码：
      <a href="https://github.com/power721/alist-tvbox" target="_blank">https://github.com/power721/alist-tvbox</a>
    </p>
    <p>
      中文文档：<a href="https://har01d.cn/#/notes/alist-tvbox" target="_blank">https://har01d.cn/#/notes/alist-tvbox</a>
    </p>
    <p>
      <a href="https://github.com/power721/alist-tvbox/blob/master/doc/README_zh.md" target="_blank">https://github.com/power721/alist-tvbox/blob/master/doc/README_zh.md</a>
    </p>
    <p>
      AList：
      <a href="https://alist.nn.ci/" target="_blank">https://alist.nn.ci/</a>
    </p>
    <p>
      Docker：
      <a href="https://hub.docker.com/r/haroldli/alist-tvbox" target="_blank">https://hub.docker.com/r/haroldli/alist-tvbox</a>
    </p>
    <p>
      <a href="https://hub.docker.com/r/haroldli/xiaoya-tvbox" target="_blank">https://hub.docker.com/r/haroldli/xiaoya-tvbox</a>
    </p>
    <p>
      使用方式：
      <code>docker run -d -p 4567:4567 --restart=always --name=alist-tvbox haroldli/alist-tvbox</code><br>
      <code>docker run -d -p 4567:4567 -p 5344:80 -e ALIST_PORT=5344 -v /etc/xiaoya:/data --restart=always --name=xiaoya-tvbox haroldli/xiaoya-tvbox:latest</code>
    </p>
    <p>
      一键部署(小雅版)：
      <code>sudo bash -c "$(curl -fsSL https://d.har01d.cn/update_xiaoya.sh)"</code><br>
    </p>
    <p>
      一键部署(开发版)：
      <code>wget https://d.har01d.cn/update_xiaoya.sh -O update_xiaoya.sh && sh ./update_xiaoya.sh -t dev</code><br>
    </p>
    <p>
      一键部署(NAS)：
      <code>sudo bash -c "$(curl -fsSL https://d.har01d.cn/update_xiaoya.sh)" -s /volume2/docker/xiaoya</code>
    </p>
  </div>
</template>
<script setup lang="ts">
</script>
