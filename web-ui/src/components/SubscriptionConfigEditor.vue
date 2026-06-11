<template>
  <div class="sub-config-editor">
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- 站点 -->
      <el-tab-pane label="站点" name="sites">
        <el-alert type="info" :closable="false" style="margin-bottom: 8px">
          <template #title>
            <p>可修改上游站点名称和排序,也可添加自定义站点。</p>
            <p>白名单模式下勾选要保留的站点,黑名单模式下勾选要保留的站点、未勾选的加入黑名单。</p>
          </template>
        </el-alert>
        <el-form-item label="过滤模式" v-if="mode === 'subscription'">
          <el-radio-group v-model="state.filterMode">
            <el-radio label="none">继承全局</el-radio>
            <el-radio label="whitelist">白名单</el-radio>
            <el-radio label="blacklist">黑名单</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="过滤模式" v-else>
          <el-radio-group v-model="state.filterMode">
            <el-radio label="none">不过滤</el-radio>
            <el-radio label="whitelist">白名单</el-radio>
            <el-radio label="blacklist">黑名单</el-radio>
          </el-radio-group>
        </el-form-item>

        <div style="margin-bottom: 8px">
          <el-button type="primary" plain @click="openSiteForm">+ 添加自定义站点</el-button>
        </div>

        <el-alert v-if="catalogError" type="warning" :closable="false" :title="catalogError" style="margin-bottom: 8px" />

        <el-collapse v-if="siteGroups.length" v-model="expandedGroups">
          <el-collapse-item v-for="group in siteGroups" :key="group.key" :name="group.key">
            <template #title>{{ group.label }}（{{ group.rows.length }}）</template>
            <el-table :data="group.rows" border style="width: 100%">
              <el-table-column :label="filterCheckboxLabel" width="60" v-if="state.filterMode !== 'none'">
                <template #default="scope">
                  <el-checkbox v-model="scope.row.enabled" />
                </template>
              </el-table-column>
              <el-table-column prop="key" label="key" width="180" />
              <el-table-column label="名称">
                <template #default="scope">
                  <el-input v-model="scope.row.name" :disabled="!isOwnRow(scope.row)" />
                </template>
              </el-table-column>
              <el-table-column label="排序" width="120">
                <template #default="scope">
                  <el-input v-model="scope.row.order" :disabled="!isOwnRow(scope.row)" placeholder="默认" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120">
                <template #default="scope">
                  <el-button v-if="isOwnRow(scope.row) && !scope.row.isCustom" link type="warning" @click="resetSiteToDefault(scope.row)">恢复默认</el-button>
                  <el-button v-if="scope.row.isCustom" link type="danger" @click="removeCustomSite(scope.row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
        <el-empty v-else description="无站点目录,可手动添加自定义站点" />

        <el-button type="primary" plain @click="openSiteForm" style="margin-top: 8px">+ 添加自定义站点</el-button>
      </el-tab-pane>

      <!-- 基础 -->
      <el-tab-pane label="基础" name="basic">
        <el-form label-width="120">
          <el-form-item label="壁纸 wallpaper">
            <el-input v-model="state.wallpaper" placeholder="图片或视频 URL, 如 https://example.com/bg.jpg" />
          </el-form-item>
          <el-form-item label="Logo">
            <el-input v-model="state.logo" placeholder="Logo 图片 URL" />
          </el-form-item>
          <el-form-item label="公告 notice">
            <el-input v-model="state.notice" placeholder="应用启动时显示的公告文字" />
          </el-form-item>
          <el-form-item label="平台标识 flags">
            <el-select v-model="state.flags" multiple filterable allow-create default-first-option placeholder="平台标识, 如 qq、youku、iqiyi (追加到上游)" style="width: 100%" />
          </el-form-item>
          <el-form-item label="广告过滤 ads">
            <el-select v-model="state.ads" multiple filterable allow-create default-first-option placeholder="广告域名, 匹配的请求将被拦截 (追加)" style="width: 100%" />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- 解析 -->
      <el-tab-pane label="解析" name="parses">
        <el-alert type="info" :closable="false" style="margin-bottom: 8px">
          <template #title>
            <p>管理影片解析规则。取消勾选可禁用上游解析器,也可添加自定义解析。</p>
            <p>解析类型: 0=嗅探, 1=Json, 2=Json扩展, 3=聚合, 4=超级解析。</p>
          </template>
        </el-alert>
        <el-table v-if="parseRows.length" :data="parseRows" border style="width: 100%">
          <el-table-column label="启用" width="60">
            <template #default="scope">
              <el-checkbox v-model="scope.row.enabled" :disabled="scope.row.isCustom" />
            </template>
          </el-table-column>
          <el-table-column label="名称">
            <template #default="scope">
              <el-input v-model="scope.row.name" :disabled="!scope.row.isCustom" />
            </template>
          </el-table-column>
          <el-table-column label="类型" width="170">
            <template #default="scope">
              <el-select v-if="scope.row.isCustom" v-model="scope.row.type">
                <el-option v-for="o in parseTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="scope">
              <el-button v-if="scope.row.isCustom" link type="danger" @click="removeCustomParse(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="无解析目录" />
        <el-button type="primary" plain @click="openParseForm" style="margin-top: 8px">+ 添加自定义解析</el-button>
      </el-tab-pane>

      <!-- Headers -->
      <el-tab-pane label="Headers" name="headers">
        <el-alert type="info" :closable="false" style="margin-bottom: 8px">
          <template #title>针对特定域名注入 HTTP 响应头, 常用于解除 CORS 限制或设置 User-Agent。</template>
        </el-alert>
        <div v-if="state.headers.length">
          <div v-for="(h, hi) in state.headers" :key="hi" style="margin-bottom: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; padding: 8px">
            <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px">
              <span style="white-space: nowrap; font-weight: 500">域名</span>
              <el-input v-model="h.host" placeholder="example.com (不含协议)" style="flex: 1" />
              <el-button link type="danger" @click="removeHeader(hi)">删除组</el-button>
            </div>
            <el-table :data="h.pairs" border size="small">
              <el-table-column label="名称" width="200">
                <template #default="scope">
                  <el-input v-model="scope.row.name" placeholder="Referer" />
                </template>
              </el-table-column>
              <el-table-column label="值">
                <template #default="scope">
                  <el-input v-model="scope.row.value" placeholder="https://example.com/" />
                </template>
              </el-table-column>
              <el-table-column label="" width="50">
                <template #default="scope">
                  <el-button link type="danger" @click="h.pairs.splice(scope.$index, 1)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button type="primary" link @click="h.pairs.push({ name: '', value: '' })" style="margin-top: 4px">+ 添加</el-button>
          </div>
        </div>
        <el-empty v-else description="无 Headers 配置" />
        <el-button type="primary" plain @click="addHeader" style="margin-top: 8px">+ 添加 Header 组</el-button>
      </el-tab-pane>

      <!-- 直播 -->
      <el-tab-pane label="直播" name="lives">
        <el-alert type="info" :closable="false" style="margin-bottom: 8px">
          <template #title>配置直播源。可内嵌频道组,也可指向外部直播列表(M3U/TXT/JSON)。EPG 支持 {name}、{id} 变量。</template>
        </el-alert>
        <div v-if="state.lives.length">
          <div v-for="(l, li) in state.lives" :key="li" style="margin-bottom: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; padding: 12px">
            <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px">
              <el-input v-model="l.name" placeholder="直播源名称 (唯一)" style="width: 160px" />
              <el-select v-model="l.type" style="width: 100px">
                <el-option :value="0" label="接口 0" />
                <el-option :value="1" label="标准 1" />
              </el-select>
              <el-select v-model="l.playerType" style="width: 120px">
                <el-option :value="0" label="系统 0" />
                <el-option :value="1" label="IJK 1" />
                <el-option :value="2" label="Exo 2" />
              </el-select>
              <el-button link type="danger" @click="state.lives.splice(li, 1)">删除</el-button>
            </div>
            <el-form label-width="80" size="small">
              <el-form-item label="地址 url"><el-input v-model="l.url" placeholder="直播源地址 (M3U/TXT/JSON)" /></el-form-item>
              <el-form-item label="UA"><el-input v-model="l.ua" placeholder="User-Agent, 如 okhttp/3.15" /></el-form-item>
              <el-form-item label="节目表 epg"><el-input v-model="l.epg" placeholder="EPG 地址, 支持 {name} {id} 变量, 多个逗号分隔" /></el-form-item>
              <el-form-item label="图标 logo"><el-input v-model="l.logo" placeholder="频道默认 Logo, 支持 {name} {id} {logo} 变量" /></el-form-item>
            </el-form>
            <!-- 高级设置 -->
            <el-collapse>
              <el-collapse-item title="高级设置" :name="'live-adv-' + li">
                <el-form label-width="100" size="small">
                  <el-form-item label="api"><el-input v-model="l.api" placeholder="直播 API 端点或爬虫类名 (如 csp_Live)" /></el-form-item>
                  <el-form-item label="ext"><el-input v-model="l.ext" placeholder="传给直播爬虫的扩展参数" /></el-form-item>
                  <el-form-item label="jar"><el-input v-model="l.jar" placeholder="Spider JAR 路径或 URL" /></el-form-item>
                  <el-form-item label="点击拦截 click"><el-input v-model="l.click" placeholder="点击拦截处理 URL 或规则" /></el-form-item>
                  <el-form-item label="来源 origin"><el-input v-model="l.origin" placeholder="请求 Origin 标头值" /></el-form-item>
                  <el-form-item label="引用 referer"><el-input v-model="l.referer" placeholder="请求 Referer 标头值" /></el-form-item>
                  <el-form-item label="时区 timeZone"><el-input v-model="l.timeZone" placeholder="如 Asia/Taipei, Asia/Shanghai" /></el-form-item>
                  <el-form-item label="超时 timeout">
                    <el-input-number v-model="l.timeout" :min="0" controls-position="right" placeholder="秒" />
                  </el-form-item>
                  <el-form-item label="启动选中 boot"><el-switch v-model="l.boot" :active-value="1" :inactive-value="0" /><span style="margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px">应用启动时自动选中此直播源</span></el-form-item>
                  <el-form-item label="跳过密码 pass"><el-switch v-model="l.pass" :active-value="1" :inactive-value="0" /><span style="margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px">跳过密码保护, 显示含密码的隐藏分组</span></el-form-item>
                </el-form>
                <!-- header -->
                <el-form-item label="请求头 header" label-width="100">
                  <div style="width: 100%">
                    <el-table :data="liveHeaderPairs(l)" border size="small">
                      <el-table-column label="名称" width="200">
                        <template #default="scope"><el-input v-model="scope.row.name" placeholder="User-Agent" /></template>
                      </el-table-column>
                      <el-table-column label="值">
                        <template #default="scope"><el-input v-model="scope.row.value" /></template>
                      </el-table-column>
                      <el-table-column label="" width="50">
                        <template #default="scope">
                          <el-button link type="danger" @click="liveHeaderPairs(l).splice(scope.$index, 1)">删除</el-button>
                        </template>
                      </el-table-column>
                    </el-table>
                    <el-button type="primary" link @click="liveHeaderPairs(l).push({ name: '', value: '' })">+ 添加</el-button>
                  </div>
                </el-form-item>
                <!-- catchup -->
                <el-form-item label="回看 catchup" label-width="100">
                  <div style="width: 100%">
                    <el-form label-width="80" size="small" :inline="true">
                      <el-form-item label="类型 type">
                        <el-select v-model="liveCatchup(l).type" style="width: 140px" @change="onCatchupChange(l)">
                          <el-option value="" label="无" />
                          <el-option value="append" label="append (追加)" />
                          <el-option value="default" label="default (替换)" />
                        </el-select>
                      </el-form-item>
                      <el-form-item label="匹配 regex">
                        <el-input v-model="liveCatchup(l).regex" placeholder="URL 匹配条件 (子串或正则)" style="width: 200px" @input="onCatchupChange(l)" />
                      </el-form-item>
                    </el-form>
                    <el-form label-width="80" size="small" :inline="true" style="margin-top: 4px">
                      <el-form-item label="模板 source">
                        <el-input v-model="liveCatchup(l).source" placeholder="时移 URL 模板, 如 ?playseek=${(b)yyyyMMddHHmmss}" style="width: 360px" @input="onCatchupChange(l)" />
                      </el-form-item>
                    </el-form>
                    <el-form label-width="80" size="small" :inline="true" style="margin-top: 4px">
                      <el-form-item label="替换 replace">
                        <el-input v-model="liveCatchup(l).replace" placeholder="原串,新串 (逗号分隔)" style="width: 360px" @input="onCatchupChange(l)" />
                      </el-form-item>
                    </el-form>
                  </div>
                </el-form-item>
              </el-collapse-item>
            </el-collapse>
            <!-- 频道组 -->
            <el-collapse>
              <el-collapse-item :title="'频道组 (' + (l.groups ? l.groups.length : 0) + ')'" :name="'live-groups-' + li">
                <el-alert type="info" :closable="false" style="margin-bottom: 8px">
                  <template #title>内嵌频道组数据。url 和 groups 二选一, 如已有外部直播列表则无需配置频道组。</template>
                </el-alert>
                <div v-for="(g, gi) in l.groups" :key="gi" style="margin-bottom: 8px; border: 1px dashed var(--el-border-color); border-radius: 4px; padding: 8px">
                  <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 4px">
                    <el-input v-model="g.name" placeholder="分组名称 (如 新闻台)" style="width: 200px" />
                    <span style="white-space: nowrap; font-size: 12px">密码保护</span>
                    <el-switch v-model="g.pass" :active-value="1" :inactive-value="0" />
                    <el-button link type="danger" @click="l.groups.splice(gi, 1)">删除组</el-button>
                  </div>
                  <el-table :data="g.channels" border size="small">
                    <el-table-column label="频道名" width="140">
                      <template #default="scope"><el-input v-model="scope.row.name" placeholder="频道名" /></template>
                    </el-table-column>
                    <el-table-column label="流地址 urls">
                      <template #default="scope">
                        <el-select v-model="scope.row.urls" multiple filterable allow-create default-first-option
                          placeholder="流地址 (可加 $线路名 后缀)" style="width: 100%" />
                      </template>
                    </el-table-column>
                    <el-table-column label="操作" width="120">
                      <template #default="scope">
                        <el-button link @click="openChannelForm(g, scope.$index)">编辑</el-button>
                        <el-button link type="danger" @click="g.channels.splice(scope.$index, 1)">删除</el-button>
                      </template>
                    </el-table-column>
                  </el-table>
                  <el-button type="primary" link @click="addChannel(g)" style="margin-top: 4px">+ 添加频道</el-button>
                </div>
                <el-button type="primary" plain @click="addGroup(l)">+ 添加频道组</el-button>
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>
        <el-empty v-else description="无直播配置" />
        <el-button type="primary" plain @click="addLive" style="margin-top: 8px">+ 添加直播源</el-button>
      </el-tab-pane>

      <!-- 网络 -->
      <el-tab-pane label="网络" name="network">
        <el-alert type="info" :closable="false" style="margin-bottom: 8px">
          <template #title>配置 DNS 加密查询、网络代理、请求拦截规则和 DNS 覆盖。不熟悉的选项可留空。</template>
        </el-alert>

        <!-- DoH -->
        <el-divider content-position="left">DNS 加密查询 (doh)</el-divider>
        <div v-for="(d, di) in state.doh" :key="'doh-' + di" style="margin-bottom: 8px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; padding: 8px">
          <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 4px">
            <el-input v-model="d.name" placeholder="名称 (如 Google DoH)" style="width: 160px" />
            <el-input v-model="d.url" placeholder="DoH 查询端点, 如 https://dns.google/dns-query" style="flex: 1" />
            <el-button link type="danger" @click="state.doh.splice(di, 1)">删除</el-button>
          </div>
          <el-form-item label="IP 地址" label-width="60" style="margin-bottom: 0">
            <el-select v-model="d.ips" multiple filterable allow-create default-first-option placeholder="服务器 IP, 用于引导解析, 如 8.8.8.8" style="width: 100%" />
          </el-form-item>
        </div>
        <el-button type="primary" plain @click="state.doh.push({ name: '', url: '', ips: [] })" style="margin-bottom: 12px">+ 添加 DoH</el-button>

        <!-- Proxy -->
        <el-divider content-position="left">网络代理 (proxy)</el-divider>
        <div v-for="(p, pi) in state.proxy" :key="'proxy-' + pi" style="margin-bottom: 8px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; padding: 8px">
          <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 4px">
            <el-input v-model="p.name" placeholder="名称 (如 全局代理)" style="width: 160px" />
            <el-button link type="danger" @click="state.proxy.splice(pi, 1)">删除</el-button>
          </div>
          <el-form-item label="适用域名" label-width="80" style="margin-bottom: 0">
            <el-select v-model="p.hosts" multiple filterable allow-create default-first-option placeholder="匹配的域名列表, 支持正则, 如 googlevideo.com 或 .* " style="width: 100%" />
          </el-form-item>
          <el-form-item label="代理地址" label-width="80" style="margin-bottom: 0">
            <el-select v-model="p.urls" multiple filterable allow-create default-first-option placeholder="代理 URL, 如 http://127.0.0.1:7890 或 socks5://user:pass@host:port" style="width: 100%" />
          </el-form-item>
        </div>
        <el-button type="primary" plain @click="state.proxy.push({ name: '', hosts: [], urls: [] })" style="margin-bottom: 12px">+ 添加代理</el-button>

        <!-- Rules -->
        <el-divider content-position="left">网络拦截规则 (rules)</el-divider>
        <div v-for="(r, ri) in state.rules" :key="'rules-' + ri" style="margin-bottom: 8px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; padding: 8px">
          <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 4px">
            <el-input v-model="r.name" placeholder="名称 (如 视频嗅探)" style="width: 160px" />
            <el-button link type="danger" @click="state.rules.splice(ri, 1)">删除</el-button>
          </div>
          <el-form-item label="匹配域名" label-width="80" style="margin-bottom: 0">
            <el-select v-model="r.hosts" multiple filterable allow-create default-first-option placeholder="触发的域名列表" style="width: 100%" />
          </el-form-item>
          <el-form-item label="提取正则" label-width="80" style="margin-bottom: 0">
            <el-select v-model="r.regex" multiple filterable allow-create default-first-option placeholder="用于提取播放 URL 的正则, 如 m3u8?token=" style="width: 100%" />
          </el-form-item>
          <el-form-item label="执行脚本" label-width="80" style="margin-bottom: 0">
            <el-select v-model="r.script" multiple filterable allow-create default-first-option placeholder="WebView 中执行的 JS 脚本" style="width: 100%" />
          </el-form-item>
          <el-form-item label="排除规则" label-width="80" style="margin-bottom: 0">
            <el-select v-model="r.exclude" multiple filterable allow-create default-first-option placeholder="排除的 URL 模式, 匹配则不触发正则" style="width: 100%" />
          </el-form-item>
        </div>
        <el-button type="primary" plain @click="state.rules.push({ name: '', hosts: [], regex: [], script: [], exclude: [] })" style="margin-bottom: 12px">+ 添加规则</el-button>

        <!-- Hosts -->
        <el-divider content-position="left">DNS 解析覆盖 (hosts)</el-divider>
        <el-select v-model="state.hostsList" multiple filterable allow-create default-first-option
          placeholder="格式: 原始域名=目标域名或IP, 如 example.com=1.2.3.4 (支持 * 通配)" style="width: 100%" />
      </el-tab-pane>

      <!-- 原始 JSON -->
      <el-tab-pane label="原始JSON" name="json">
        <el-input v-model="jsonText" type="textarea" :rows="18" />
        <el-alert v-if="jsonError" type="error" :closable="false" :title="jsonError" style="margin-top: 8px" />
        <div style="margin-top: 8px">
          <el-button @click="applyJson">从 JSON 应用到表单</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 自定义站点表单 -->
    <el-dialog v-model="siteFormVisible" title="自定义站点" width="640px" append-to-body destroy-on-close>
      <el-form :model="siteForm" label-width="120" style="max-height: 60vh; overflow-y: auto">
        <el-form-item label="标识 key" required><el-input v-model="siteForm.key" placeholder="唯一标识, 不可重复" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="siteForm.name" placeholder="显示名称" /></el-form-item>
        <el-form-item label="类型 type">
          <el-select v-model="siteForm.type">
            <el-option v-for="o in siteTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="接口 api"><el-input v-model="siteForm.api" placeholder="API 端点 URL 或爬虫类名 (如 csp_MySource)" /></el-form-item>
        <el-form-item label="扩展 ext"><el-input v-model="siteForm.ext" type="textarea" :rows="3" placeholder="传给爬虫的扩展数据, 可为字符串或 JSON" /></el-form-item>
        <el-form-item label="Spider jar"><el-input v-model="siteForm.jar" placeholder="Spider JAR 路径或 URL, 覆盖全局 spider" /></el-form-item>
        <el-form-item label="搜索 searchable">
          <el-select v-model="siteForm.searchable">
            <el-option :value="0" label="不可搜索(0)" />
            <el-option :value="1" label="可搜索(1)" />
            <el-option :value="2" label="聚合搜索(2)" />
          </el-select>
        </el-form-item>
        <el-form-item label="快速搜索"><el-switch v-model="siteForm.quickSearch" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="筛选 filterable"><el-switch v-model="siteForm.filterable" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="线路切换"><el-switch v-model="siteForm.changeable" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="卡片风格 style">
          <el-select v-model="siteForm.styleType" style="width: 140px">
            <el-option value="" label="默认" />
            <el-option value="rect" label="rect 矩形" />
            <el-option value="oval" label="oval 圆形" />
            <el-option value="list" label="list 列表" />
          </el-select>
          <el-input v-model="siteForm.styleRatio" placeholder="宽高比, 如 1.33" style="width: 120px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="排序 order"><el-input v-model="siteForm.order" placeholder="数字, 越小越靠前" /></el-form-item>
        <el-divider content-position="left">高级设置</el-divider>
        <el-form-item label="超时 timeout">
          <el-input-number v-model="siteForm.timeout" :min="0" controls-position="right" placeholder="请求超时秒数" />
        </el-form-item>
        <el-form-item label="索引模式 indexs">
          <el-switch v-model="siteForm.indexs" :active-value="1" :inactive-value="0" />
          <span style="margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px">作为索引来源使用</span>
        </el-form-item>
        <el-form-item label="播放前缀 playUrl"><el-input v-model="siteForm.playUrl" placeholder="播放 URL 前缀或转换规则" /></el-form-item>
        <el-form-item label="点击拦截 click"><el-input v-model="siteForm.click" placeholder="点击拦截处理 URL 或规则" /></el-form-item>
        <el-form-item label="分类白名单">
          <el-select v-model="siteForm.categories" multiple filterable allow-create default-first-option
            placeholder="仅显示这些分类, 留空显示全部" style="width: 100%" />
        </el-form-item>
        <el-form-item label="请求头 header">
          <div style="width: 100%">
            <el-table :data="siteForm.headerPairs" border size="small">
              <el-table-column label="名称" width="200">
                <template #default="scope"><el-input v-model="scope.row.name" placeholder="User-Agent" /></template>
              </el-table-column>
              <el-table-column label="值">
                <template #default="scope"><el-input v-model="scope.row.value" /></template>
              </el-table-column>
              <el-table-column label="" width="50">
                <template #default="scope">
                  <el-button link type="danger" @click="siteForm.headerPairs.splice(scope.$index, 1)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button type="primary" link @click="siteForm.headerPairs.push({ name: '', value: '' })">+ 添加</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="siteFormVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmSiteForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 自定义解析表单 -->
    <el-dialog v-model="parseFormVisible" title="自定义解析" width="560px" append-to-body destroy-on-close>
      <el-form :model="parseForm" label-width="100">
        <el-form-item label="名称" required><el-input v-model="parseForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="parseForm.type">
            <el-option v-for="o in parseTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="地址 url"><el-input v-model="parseForm.url" placeholder="解析 API 端点, 待解析 URL 作为后缀参数" /></el-form-item>
        <el-form-item label="适用平台 flag">
          <el-select v-model="parseForm.flag" multiple filterable allow-create default-first-option placeholder="适用的平台标识, 如 qq、youku" style="width: 100%" />
        </el-form-item>
        <el-divider content-position="left">高级设置</el-divider>
        <el-form-item label="请求头 ext.header">
          <div style="width: 100%">
            <el-table :data="parseForm.headerPairs" border size="small">
              <el-table-column label="名称" width="200">
                <template #default="scope"><el-input v-model="scope.row.name" placeholder="Referer" /></template>
              </el-table-column>
              <el-table-column label="值">
                <template #default="scope"><el-input v-model="scope.row.value" /></template>
              </el-table-column>
              <el-table-column label="" width="50">
                <template #default="scope">
                  <el-button link type="danger" @click="parseForm.headerPairs.splice(scope.$index, 1)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button type="primary" link @click="parseForm.headerPairs.push({ name: '', value: '' })">+ 添加</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="parseFormVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmParseForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 频道详情 -->
    <el-dialog v-model="channelFormVisible" title="频道详情" width="560px" append-to-body destroy-on-close>
      <el-form :model="channelForm" label-width="100" style="max-height: 60vh; overflow-y: auto">
        <el-form-item label="频道名"><el-input v-model="channelForm.name" /></el-form-item>
        <el-form-item label="频道号 number"><el-input v-model="channelForm.number" placeholder="显示用频道号" /></el-form-item>
        <el-form-item label="图标 logo"><el-input v-model="channelForm.logo" placeholder="频道 Logo URL" /></el-form-item>
        <el-form-item label="节目表 epg"><el-input v-model="channelForm.epg" placeholder="此频道专属 EPG URL" /></el-form-item>
        <el-form-item label="UA"><el-input v-model="channelForm.ua" placeholder="User-Agent" /></el-form-item>
        <el-form-item label="格式 format"><el-input v-model="channelForm.format" placeholder="MIME type, 如 application/x-mpegURL" /></el-form-item>
        <el-form-item label="来源 origin"><el-input v-model="channelForm.origin" placeholder="请求 Origin 标头" /></el-form-item>
        <el-form-item label="引用 referer"><el-input v-model="channelForm.referer" placeholder="请求 Referer 标头" /></el-form-item>
        <el-form-item label="EPG ID tvgId"><el-input v-model="channelForm.tvgId" placeholder="XMLTV 格式频道 ID" /></el-form-item>
        <el-form-item label="EPG 名称 tvgName"><el-input v-model="channelForm.tvgName" placeholder="XMLTV 格式频道名称" /></el-form-item>
        <el-form-item label="解析 parse"><el-switch v-model="channelForm.parse" :active-value="1" :inactive-value="0" /><span style="margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px">是否需要解析此频道 URL</span></el-form-item>
        <el-form-item label="点击拦截 click"><el-input v-model="channelForm.click" placeholder="点击拦截处理" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="channelFormVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmChannelForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import {
  parseOverride,
  detectFilterMode,
  disabledSiteKeys,
  whitelistKeys,
  disabledParseNames,
  siteOverrideMap,
  serialize,
  stringify,
  buildHeaderRows,
  buildLiveRows,
  buildDohRows,
  buildProxyRows,
  buildRulesRows,
} from '@/utils/subscriptionConfig.mjs'

const props = withDefaults(
  defineProps<{
    modelValue: string
    mode?: 'subscription' | 'global'
    referenceSid?: string
    token?: string
  }>(),
  { mode: 'subscription', referenceSid: '', token: '' }
)
defineEmits<{ 'update:modelValue': [string] }>()

const siteTypeOptions = [
  { value: 0, label: 'CMS(xml) 0' },
  { value: 1, label: 'CMS(json) 1' },
  { value: 3, label: 'Spider 3' },
  { value: 4, label: '外部 4' },
]
const parseTypeOptions = [
  { value: 0, label: '嗅探 0 (WebView)' },
  { value: 1, label: 'Json 1 (GET 取 url)' },
  { value: 2, label: 'Json扩展 2' },
  { value: 3, label: '聚合 3' },
  { value: 4, label: '超级解析 4 (并行)' },
]

const activeTab = ref('sites')
const catalogError = ref('')
const jsonText = ref('')
const jsonError = ref('')
const siteFormVisible = ref(false)
const parseFormVisible = ref(false)
const channelFormVisible = ref(false)

const state = reactive<any>({
  filterMode: 'none',
  sites: [],
  parses: [],
  wallpaper: '',
  logo: '',
  notice: '',
  flags: [],
  ads: [],
  headers: [],
  lives: [],
  doh: [],
  proxy: [],
  rules: [],
  hostsList: [],
})
// 未建模键的保留载体
let baseConfig: Record<string, any> = {}

const filterCheckboxLabel = '保留'
const siteRows = ref<any[]>([])
const parseRows = ref<any[]>([])
const expandedGroups = ref<string[]>(['upstream', 'custom'])
const GROUP_ORDER = [
  { key: 'upstream', label: '上游源' },
  { key: 'custom', label: '自定义站点' },
  { key: 'builtin', label: '内置源' },
  { key: 'plugin', label: '插件源' },
]
const groupKeyOf = (row: any) =>
  row.isCustom ? 'custom' : row.origin === 'builtin' ? 'builtin' : row.origin === 'plugin' ? 'plugin' : 'upstream'
const isOwnRow = (row: any) => row.origin === 'upstream' || row.isCustom
const siteGroups = computed(() =>
  GROUP_ORDER.map((g) => ({ ...g, rows: siteRows.value.filter((r) => groupKeyOf(r) === g.key) })).filter(
    (g) => g.rows.length
  )
)

const siteForm = reactive<any>({})
const parseForm = reactive<any>({})
const channelForm = reactive<any>({})
let _channelGroup: any = null
let _channelIndex = -1

function resetSiteForm() {
  Object.assign(siteForm, {
    key: '', name: '', type: 3, api: '', ext: '', jar: '',
    searchable: 1, quickSearch: 1, filterable: 1, changeable: 0,
    styleType: '', styleRatio: '', order: '',
    timeout: '', indexs: 0, playUrl: '', click: '',
    categories: [], headerPairs: [],
  })
}
function resetParseForm() {
  Object.assign(parseForm, { name: '', type: 0, url: '', flag: [], headerPairs: [] })
}

const load = async () => {
  const parsed = parseOverride(props.modelValue)
  baseConfig = parsed === null ? {} : JSON.parse(JSON.stringify(parsed))
  if (parsed === null) {
    jsonError.value = '原始内容不是合法 JSON,已切到 JSON 标签'
    jsonText.value = props.modelValue
    activeTab.value = 'json'
  }
  const config = parsed === null ? {} : parsed

  state.filterMode = detectFilterMode(config)
  state.wallpaper = config.wallpaper || ''
  state.logo = config.logo || ''
  state.notice = config.notice || ''
  state.flags = Array.isArray(config.flags) ? [...config.flags] : []
  state.ads = Array.isArray(config.ads) ? [...config.ads] : []
  state.headers = buildHeaderRows(config)
  state.lives = buildLiveRows(config)
  state.doh = buildDohRows(config)
  state.proxy = buildProxyRows(config)
  state.rules = buildRulesRows(config)
  state.hostsList = Array.isArray(config.hosts) ? [...config.hosts] : []

  // catalog
  let catalog: any = { sites: [], parses: [] }
  catalogError.value = ''
  if (props.referenceSid !== '' && props.referenceSid != null) {
    try {
      const { data } = await axios.get(`/api/subscriptions/${props.referenceSid}/catalog`)
      catalog = data
    } catch {
      catalogError.value = '获取站点目录失败,可手动添加自定义站点'
    }
  }
  buildRows(config, catalog)
}

function buildRows(config: Record<string, any>, catalog: any) {
  const disabled = new Set(disabledSiteKeys(config))
  const white = new Set(whitelistKeys(config))
  const overrides = siteOverrideMap(config)
  const catalogKeys = new Set<string>((catalog.sites || []).map((s: any) => String(s.key)))

  const rows: any[] = []
  for (const c of catalog.sites || []) {
    const key = String(c.key)
    const ov = overrides[key] || {}
    rows.push({
      key,
      origin: c.origin,
      isCustom: false,
      enabled: state.filterMode === 'whitelist' ? white.has(key) : !disabled.has(key),
      name: ov.name != null ? ov.name : c.name,
      originalName: c.name,
      hadNameOverride: ov.name != null,
      order: ov.order != null ? ov.order : '',
    })
  }
  // catalog 缺失但被禁用/白名单引用的 key -> 合成行
  const known = new Set(rows.map((r) => r.key))
  ;[...disabled, ...white].forEach((key) => {
    if (!known.has(key)) {
      const ov = overrides[key] || {}
      rows.push({
        key, origin: 'upstream', isCustom: false,
        enabled: state.filterMode === 'whitelist' ? white.has(key) : !disabled.has(key),
        name: ov.name != null ? ov.name : key, originalName: key, hadNameOverride: ov.name != null,
        order: ov.order != null ? ov.order : '',
      })
      known.add(key)
    }
  })
  // 自定义站点(config.sites 中 key ∉ catalog 且为完整对象)
  if (Array.isArray(config.sites)) {
    for (const s of config.sites) {
      const key = s && s.key != null ? String(s.key) : ''
      if (!key || catalogKeys.has(key) || known.has(key)) continue
      if (s.type != null || s.api != null) {
        rows.push({
          ...s, key, origin: 'custom', isCustom: true, enabled: true,
          styleType: s.style?.type || '', styleRatio: s.style?.ratio ?? '',
        })
        known.add(key)
      }
    }
  }
  siteRows.value = rows
  state.sites = rows

  // 解析
  const disabledParses = new Set(disabledParseNames(config))
  const prows: any[] = []
  for (const p of catalog.parses || []) {
    prows.push({ name: p.name, isCustom: false, enabled: !disabledParses.has(p.name) })
  }
  if (Array.isArray(config.parses)) {
    for (const p of config.parses) {
      if (p && p.name) {
        prows.push({
          name: p.name, isCustom: true, enabled: true, type: p.type ?? 0,
          url: p.url || '', flag: p.ext?.flag || [], header: p.ext?.header || {},
        })
      }
    }
  }
  parseRows.value = prows
  state.parses = prows
}

function openSiteForm() {
  resetSiteForm()
  siteFormVisible.value = true
}
function confirmSiteForm() {
  if (!siteForm.key) {
    ElMessage.warning('请输入 key')
    return
  }
  const headerObj: any = {}
  for (const p of siteForm.headerPairs || []) {
    if (p.name) headerObj[p.name] = p.value || ''
  }
  const row: any = {
    key: siteForm.key, name: siteForm.name, type: siteForm.type, api: siteForm.api,
    ext: siteForm.ext, jar: siteForm.jar, searchable: siteForm.searchable,
    quickSearch: siteForm.quickSearch, filterable: siteForm.filterable, changeable: siteForm.changeable,
    order: siteForm.order, origin: 'custom', isCustom: true, enabled: true,
    timeout: siteForm.timeout || undefined,
    indexs: siteForm.indexs || undefined,
    playUrl: siteForm.playUrl || undefined,
    click: siteForm.click || undefined,
    categories: siteForm.categories.length ? [...siteForm.categories] : undefined,
    header: Object.keys(headerObj).length ? headerObj : undefined,
  }
  if (siteForm.styleType) row.style = { type: siteForm.styleType, ratio: Number(siteForm.styleRatio) || undefined }
  if (typeof row.ext === 'string' && row.ext.trim().startsWith('{')) {
    try { row.ext = JSON.parse(row.ext) } catch { /* keep string */ }
  }
  siteRows.value.push(row)
  state.sites = siteRows.value
  siteFormVisible.value = false
}
function removeCustomSite(row: any) {
  siteRows.value = siteRows.value.filter((r) => r !== row)
  state.sites = siteRows.value
}
function resetSiteToDefault(row: any) {
  row.name = row.originalName
  row.hadNameOverride = false
  row.order = ''

}

function openParseForm() {
  resetParseForm()
  parseFormVisible.value = true
}
function confirmParseForm() {
  if (!parseForm.name) {
    ElMessage.warning('请输入名称')
    return
  }
  const headerObj: any = {}
  for (const p of parseForm.headerPairs || []) {
    if (p.name) headerObj[p.name] = p.value || ''
  }
  parseRows.value.push({
    name: parseForm.name, type: parseForm.type, url: parseForm.url,
    flag: [...parseForm.flag], header: headerObj, isCustom: true, enabled: true,
  })
  state.parses = parseRows.value
  parseFormVisible.value = false
}
function removeCustomParse(row: any) {
  parseRows.value = parseRows.value.filter((r) => r !== row)
  state.parses = parseRows.value
}

function addHeader() {
  state.headers.push({ host: '', pairs: [{ name: '', value: '' }] })
}
function removeHeader(index: number) {
  state.headers.splice(index, 1)
}

function addLive() {
  state.lives.push({
    name: '', type: 0, url: '', playerType: 2, ua: '', epg: '', logo: '',
    api: '', ext: '', jar: '', click: '', origin: '', referer: '',
    timeZone: '', timeout: '', header: {}, catchup: null, boot: 0, pass: 0,
    groups: [],
  })
}

// --- Live helpers ---

function liveHeaderPairs(live: any) {
  if (!live._headerPairs) {
    live._headerPairs = Object.entries(live.header || {}).map(([name, value]) => ({ name, value: String(value) }))
  }
  return live._headerPairs
}

const _catchupCache = new WeakMap()
function liveCatchup(live: any) {
  if (!_catchupCache.has(live)) {
    const c = live.catchup || {}
    _catchupCache.set(live, reactive({
      type: c.type || '',
      regex: c.regex || '',
      source: c.source || '',
      replace: c.replace || '',
    }))
  }
  return _catchupCache.get(live)
}

function onCatchupChange(live: any) {
  const c = liveCatchup(live)
  if (c.type || c.source) {
    live.catchup = { type: c.type, regex: c.regex, source: c.source, replace: c.replace }
  } else {
    live.catchup = null
  }
}

function addGroup(live: any) {
  if (!Array.isArray(live.groups)) live.groups = []
  live.groups.push({ name: '', pass: 0, channels: [] })
}

function addChannel(group: any) {
  group.channels.push({
    name: '', urls: [], number: '', logo: '', epg: '', ua: '',
    format: '', origin: '', referer: '', tvgId: '', tvgName: '',
    parse: 0, click: '', header: {}, catchup: null, drm: null,
  })
}

function openChannelForm(group: any, index: number) {
  _channelGroup = group
  _channelIndex = index
  const ch = group.channels[index]
  Object.assign(channelForm, {
    name: ch.name, number: ch.number ?? '', logo: ch.logo || '',
    epg: ch.epg || '', ua: ch.ua || '', format: ch.format || '',
    origin: ch.origin || '', referer: ch.referer || '',
    tvgId: ch.tvgId || '', tvgName: ch.tvgName || '',
    parse: ch.parse ?? 0, click: ch.click || '',
  })
  channelFormVisible.value = true
}

function confirmChannelForm() {
  if (_channelGroup && _channelIndex >= 0) {
    const ch = _channelGroup.channels[_channelIndex]
    Object.assign(ch, {
      name: channelForm.name, number: channelForm.number || undefined,
      logo: channelForm.logo || undefined, epg: channelForm.epg || undefined,
      ua: channelForm.ua || undefined, format: channelForm.format || undefined,
      origin: channelForm.origin || undefined, referer: channelForm.referer || undefined,
      tvgId: channelForm.tvgId || undefined, tvgName: channelForm.tvgName || undefined,
      parse: channelForm.parse, click: channelForm.click || undefined,
    })
  }
  channelFormVisible.value = false
}

function syncLiveHeaders() {
  for (const l of state.lives) {
    if (l._headerPairs) {
      const h: any = {}
      for (const p of l._headerPairs) {
        if (p.name) h[p.name] = p.value || ''
      }
      l.header = h
    }
  }
}

function onTabChange(name: string) {
  if (name === 'json') {
    syncLiveHeaders()
    jsonText.value = JSON.stringify(serialize(baseConfig, state), null, 2)
    jsonError.value = ''
  }
}
function applyJson() {
  const parsed = parseOverride(jsonText.value)
  if (parsed === null) {
    jsonError.value = 'JSON 格式错误'
    return
  }
  baseConfig = JSON.parse(JSON.stringify(parsed))
  jsonError.value = ''
  const catalog = {
    sites: siteRows.value.filter((r) => !r.isCustom).map((r) => ({ key: r.key, name: r.originalName, origin: r.origin })),
    parses: parseRows.value.filter((p) => !p.isCustom).map((p) => ({ name: p.name })),
  }
  state.wallpaper = parsed.wallpaper || ''
  state.logo = parsed.logo || ''
  state.notice = parsed.notice || ''
  state.flags = Array.isArray(parsed.flags) ? [...parsed.flags] : []
  state.ads = Array.isArray(parsed.ads) ? [...parsed.ads] : []
  state.headers = buildHeaderRows(parsed)
  state.lives = buildLiveRows(parsed)
  state.filterMode = detectFilterMode(parsed)
  state.doh = buildDohRows(parsed)
  state.proxy = buildProxyRows(parsed)
  state.rules = buildRulesRows(parsed)
  state.hostsList = Array.isArray(parsed.hosts) ? [...parsed.hosts] : []
  buildRows(parsed, catalog)
  ElMessage.success('已应用到表单')
}

// 对外:保存时取序列化结果。停留在「原始JSON」标签时直接用该 JSON
// (支持清空/直接编辑后保存,无需先点"从 JSON 应用到表单");非法 JSON 返回 null 以阻止保存
function getValue(): string | null {
  if (activeTab.value === 'json') {
    const parsed = parseOverride(jsonText.value)
    if (parsed === null) {
      jsonError.value = 'JSON 格式错误,请修正后再保存'
      return null
    }
    jsonError.value = ''
    return stringify(parsed)
  }
  syncLiveHeaders()
  return stringify(serialize(baseConfig, state))
}
defineExpose({ getValue, reload: load })

watch(
  () => [props.modelValue, props.referenceSid],
  () => load(),
  { immediate: true }
)
</script>

<style scoped>
.sub-config-editor {
  min-height: 360px;
}

/* 限制标签内容高度,内容超出时滚动,标签头保持可见 */
.sub-config-editor :deep(.el-tabs__content) {
  max-height: 55vh;
  overflow-y: auto;
  padding-right: 6px;
}
</style>
