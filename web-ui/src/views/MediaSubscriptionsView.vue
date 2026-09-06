<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">追剧订阅</h1>
      <div class="page-actions">
        <el-button @click="loadAll">刷新</el-button>
        <el-button @click="openNotify">设置</el-button>
        <el-button @click="exportSubs">导出</el-button>
        <el-button @click="importVisible = true">导入</el-button>
        <el-button @click="openNavigation">片单追更</el-button>
        <el-button type="primary" @click="handleAdd">新建订阅</el-button>
      </div>
    </div>

    <div class="stats-row" v-if="stats">
      <div class="stat-card"><div class="stat-value">{{ stats.total }}</div><div class="stat-label">订阅</div></div>
      <div class="stat-card"><div class="stat-value">{{ stats.active }}</div><div class="stat-label">连载中</div></div>
      <div class="stat-card"><div class="stat-value">{{ stats.todayNewEpisodes }}</div><div class="stat-label">今日更新</div></div>
      <div class="stat-card"><div class="stat-value">{{ stats.ended }}</div><div class="stat-label">已完结</div></div>
      <div class="stat-card danger"><div class="stat-value">{{ stats.error }}</div><div class="stat-label">异常</div></div>
    </div>

    <div class="page-card schedule-card" v-if="scheduleDays.some(d => d.items.length)">
      <div class="schedule-strip">
        <div v-for="day in scheduleDays" :key="day.date" class="schedule-day" :class="{today: day.today}">
          <div class="schedule-day-header">{{ day.label }} <span class="sub-text">{{ day.date }}</span></div>
          <div v-for="(item, idx) in day.items" :key="idx" class="schedule-item" :class="{paused: item.paused}">
            <span class="schedule-clock">{{ formatClock(item.airTime) }}</span>{{ item.name }}<template v-if="item.episodes"> 第{{ item.episodes }}集</template>
          </div>
          <div v-if="!day.items.length" class="schedule-empty">—</div>
        </div>
      </div>
    </div>

    <div class="page-card inbox-card" v-if="inboxItems.length">
      <div class="inbox-header" @click="inboxExpanded = !inboxExpanded">
        <b>今日/近日更新({{ inboxItems.length }})</b>
        <el-button link type="primary">{{ inboxExpanded ? '收起' : '展开' }}</el-button>
      </div>
      <el-timeline v-if="inboxExpanded" class="inbox-timeline">
        <el-timeline-item v-for="item in inboxItems.slice(0, 20)" :key="item.name + item.createdTime"
                          :timestamp="formatTime(item.createdTime)" :type="eventType(item.type)">
          {{ item.name }} · {{ eventTypeName(item.type) }} · {{ item.detail }}
        </el-timeline-item>
      </el-timeline>
    </div>

    <div class="page-card">
      <div class="batch-bar" v-if="subscriptions.length">
        <el-select v-model="statusFilter" size="small" style="width: 110px" placeholder="全部状态">
          <el-option label="全部状态" value=""/>
          <el-option label="连载中" value="ACTIVE"/>
          <el-option label="已完结" value="ENDED"/>
          <el-option label="已暂停" value="PAUSED"/>
          <el-option label="异常" value="ERROR"/>
        </el-select>
        <el-divider direction="vertical"/>
        <el-button size="small" @click="selectAll">全选</el-button>
        <el-button size="small" @click="selectNone">全不选</el-button>
        <el-button size="small" @click="invertSelection">反选</el-button>
        <el-divider direction="vertical"/>
        <el-button size="small" type="primary" :disabled="!selected.length" @click="batch('check')">批量巡检</el-button>
        <el-button size="small" :disabled="!selected.length" @click="batch('pause')">批量暂停</el-button>
        <el-button size="small" :disabled="!selected.length" @click="batch('resume')">批量恢复</el-button>
        <el-button size="small" type="danger" :disabled="!selected.length" @click="batch('delete')">批量删除</el-button>
        <span class="sub-text" style="margin-left: 10px">已选 {{ selected.length }} 项</span>
      </div>
      <div class="table-scroll-wrapper">
        <el-table ref="tableRef" :data="filteredSubscriptions" border style="width: 100%; min-width: 1100px" v-loading="loading"
                  @selection-change="(rows: any[]) => selected = rows">
          <el-table-column type="selection" width="42"/>
          <el-table-column label="剧名" min-width="230">
            <template #default="scope">
              <div class="name-cell">
                <el-image :src="scope.row.cover" fit="cover" class="cover cover-click" @click="showDetail(scope.row)">
                  <template #error>
                    <div class="cover cover-placeholder cover-click" @click="showDetail(scope.row)">
                      {{ scope.row.name.charAt(0) }}
                    </div>
                  </template>
                </el-image>
                <div>
                  <div>
                    <a class="name-link" @click="showDetail(scope.row)">{{ displayName(scope.row) }}</a>
                  </div>
                  <div class="sub-text">
                    {{ scope.row.activeResourceTitle || scope.row.keyword }}
                    <el-tag v-if="scope.row.gapCount" size="small" type="warning" style="margin-left:4px">补缺×{{ scope.row.gapCount }}</el-tag>
                  </div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="scope">
              <el-tag :type="statusType(scope.row.status)" size="small">{{ statusText(scope.row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="进度" width="170">
            <template #default="scope">
              <span>{{ progressTotal(scope.row) ? `${scope.row.currentEpisodes ?? 0} / ${progressTotal(scope.row)} 集` : `已更新至 ${scope.row.currentEpisodes ?? 0} 集` }}</span>
              <div class="sub-text danger" v-if="scope.row.missingEpisodes && scope.row.missingEpisodes.length">
                缺第 {{ compactNumbers(scope.row.missingEpisodes) }} 集
              </div>
              <div class="sub-text" v-else-if="scope.row.officialEpisodes && airedInSeason(scope.row) > (scope.row.currentEpisodes ?? 0)
                  && airedInSeason(scope.row) <= (progressTotal(scope.row) ?? airedInSeason(scope.row))">
                官方已播 {{ airedInSeason(scope.row) }} 集
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="mode" label="模式" width="80">
            <template #default="scope">
              <el-tag size="small" :type="scope.row.mode === 'TRANSFER' ? 'success' : 'info'">
                {{ scope.row.mode === 'TRANSFER' ? '转存' : '挂载' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="resourceCount" label="候选" width="65"/>
          <el-table-column label="巡检/播出" width="210">
            <template #default="scope">
              <div class="sub-text" v-if="scope.row.nextAirTime">下集播出:{{ formatTime(scope.row.nextAirTime) }}</div>
              <div class="sub-text">下次巡检:{{ formatTime(scope.row.nextCheckTime) }}</div>
              <div class="sub-text">上次巡检:{{ formatTime(scope.row.lastCheckTime) }}</div>
            </template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="280">
            <template #default="scope">
              <el-button link type="primary" size="small" @click="checkNow(scope.row)">巡检</el-button>
              <el-button link type="primary" size="small" @click="showResources(scope.row)">候选源</el-button>
              <el-button link type="primary" size="small" @click="showEpisodes(scope.row)">集数</el-button>
              <el-button link type="primary" size="small" @click="showEvents(scope.row)">动态</el-button>
              <el-button link type="primary" size="small" @click="togglePause(scope.row)">
                {{ scope.row.status === 'PAUSED' ? '恢复' : '暂停' }}
              </el-button>
              <el-button v-if="scope.row.mode === 'TRANSFER'" link type="success" size="small" @click="transferNow(scope.row)">转存</el-button>
              <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
              <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="formVisible" :title="form.id ? '编辑订阅' : '新建订阅'" width="750" top="3vh">
      <el-form :model="form" label-width="120">
        <el-form-item label="剧名" required>
          <el-input v-model="form.name" placeholder="展示名称,如:边水往事"/>
        </el-form-item>
        <el-form-item label="搜索词">
          <el-input v-model="form.keyword" placeholder="默认同剧名;资源命名差异大时可填别名"/>
        </el-form-item>
        <el-form-item label="自定义搜索词">
          <el-select v-model="form.customKeywords" multiple allow-create filterable
                     placeholder="英文名/别名/简繁写法等,回车添加,至多5个"/>
        </el-form-item>
        <el-form-item label="条目链接">
          <div class="meta-search">
            <el-input v-model="metaLink" placeholder="粘贴豆瓣/TMDB/Bangumi/腾讯视频条目链接,自动识别"
                      @keyup.enter="resolveLink"/>
            <el-button @click="resolveLink" :loading="resolvingLink">解析</el-button>
          </div>
          <span v-if="form.metaId" class="sub-text">已绑定:{{ providerName(form.metaProvider) }} {{ form.metaId }}</span>
        </el-form-item>
        <el-form-item label="元数据条目">
          <el-tabs v-model="metaProvider" style="width: 100%">
            <el-tab-pane label="TMDB" name="tmdb"/>
            <el-tab-pane label="豆瓣" name="douban"/>
            <el-tab-pane label="Bangumi" name="bangumi"/>
            <el-tab-pane label="官方平台" name="official"/>
            <el-tab-pane label="全部" name=""/>
          </el-tabs>
          <div class="meta-search">
            <el-input v-model="metaKeyword" placeholder="搜索条目(官方集数/完结判定/播出日程/封面)" @keyup.enter="searchMeta"/>
            <el-button @click="searchMeta" :loading="metaSearching">搜索</el-button>
          </div>
          <div v-if="metaResults.length" class="meta-results">
            <div v-for="item in metaResults" :key="item.provider + item.id" class="meta-item"
                 :class="{ selected: form.metaProvider === item.provider && form.metaId === item.id }"
                 @click="selectMeta(item)">
              <el-image :src="item.cover" fit="cover" class="meta-cover">
                <template #error><div class="meta-cover"></div></template>
              </el-image>
              <div class="meta-info">
                <div>{{ item.name }}({{ item.year }}){{ item.score ? ' ★' + item.score : '' }}</div>
                <div class="sub-text">{{ providerName(item.provider) }} · {{ item.id }}</div>
              </div>
            </div>
          </div>
          <div v-if="form.metaId" class="sub-text">已选:{{ providerName(form.metaProvider) }} {{ form.metaId }}</div>
        </el-form-item>
        <el-form-item label="季">
          <el-input-number v-model="form.season" :min="1" :max="50"/>
        </el-form-item>
        <el-form-item label="季起始集号">
          <el-input-number v-model="form.seasonStartEpisode" :min="0" :max="9999"/>
          <span class="sub-text" style="margin-left:8px">本季第 1 集对应全剧第 N 集 —— 元数据全剧连续集号而网盘按季内编号时用(如一念永恒);0/空 = 关闭;修改后集数记录会重置重扫</span>
        </el-form-item>
        <el-form-item label="期望集数">
          <el-input-number v-model="form.expectedEpisodes" :min="0" :max="9999"/>
          <span class="sub-text" style="margin-left:8px">0/空 = 用官方总集数,均无则不自动完结</span>
        </el-form-item>
        <el-form-item label="总集数锁定">
          <el-input-number v-model="form.manualTotalEpisodes" :min="0" :max="9999"/>
          <span class="sub-text" style="margin-left:8px">官方总集数不可信时(桥接污染/反复横跳)手动纠正:缺集/完结/分母以此为准;0/空 = 跟随官方</span>
        </el-form-item>
        <el-form-item label="资源模式">
          <el-radio-group v-model="form.mode">
            <el-radio value="FOLLOW">挂载追更(免账号)</el-radio>
            <el-radio value="TRANSFER">自动转存到我的网盘</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.mode === 'TRANSFER'" label="转存网盘">
          <el-select v-model="form.accountIds" multiple placeholder="可多选,同时转存到多个网盘" style="width: 100%">
            <el-option v-for="account in transferAccounts" :key="'pan:' + account.id"
                       :label="account.name + '(' + account.type + ')'" :value="'pan:' + account.id"/>
            <el-option v-for="account in aliAccounts" :key="'ali:' + account.id"
                       :label="(account.nickname || '阿里#' + account.id) + '(阿里)'"
                       :value="'ali:' + account.id" :disabled="!aliSelectable(account)"/>
          </el-select>
          <span class="sub-text">转存到各网盘 /我的追剧/ 目录下;全部失败才降级挂载模式</span>
        </el-form-item>
        <el-form-item v-if="form.mode === 'TRANSFER'" label="跨网盘转存">
          <el-switch v-model="form.crossDrive"/>
          <span class="sub-text" style="margin-left:8px">默认仅同盘转存(快而稳);开启后跨盘也转(慢,走服务端中转);AList 跨盘秒传配置允许的方向不受此开关限制</span>
        </el-form-item>
        <el-form-item v-if="form.mode === 'TRANSFER'" label="磁力兜底">
          <el-switch v-model="form.magnetOffline"/>
          <span class="sub-text" style="margin-left:8px">缺集长期补不上时,用磁力链接经离线下载补集(转存优先,穷尽后才使用);需先在网盘账号配置中开启离线下载</span>
          <span v-if="store.admin && form.mode === 'TRANSFER' && offlineAccountLabel" class="sub-text" style="display:block;width:100%">
            当前离线下载: {{ offlineAccountLabel }}
          </span>
        </el-form-item>
        <el-form-item label="巡检周期(时)">
          <el-input-number v-model="form.checkIntervalHours" :min="1" :max="168"/>
          <span class="sub-text" style="margin-left:8px">绑定元数据后按播出日程自动调度</span>
        </el-form-item>
        <el-form-item label="播出时刻">
          <el-time-select v-model="form.customAirClock" start="00:00" end="23:45" step="00:15"
                          placeholder="自动(官方日程/平台桥接,默认 20:00)" clearable style="width: 160px"/>
          <span class="sub-text" style="margin-left:8px">官方只给日期没给时刻的剧按 20:00 兜底;确认实际排播后手动校正,清空恢复自动</span>
        </el-form-item>
        <el-form-item label="更新日">
          <el-select v-model="form.airWeekdays" multiple placeholder="不限制(按官方日程/巡检周期)">
            <el-option v-for="(label, idx) in ['周一', '周二', '周三', '周四', '周五', '周六', '周日']"
                       :key="idx" :label="label" :value="idx + 1"/>
          </el-select>
          <span class="sub-text" style="margin-left:8px">固定周几更新(欧美剧/追番):巡检只落在这些天的播出时刻,官方日程缺失/不准时用;清空恢复自动</span>
        </el-form-item>
        <el-form-item label="主网盘(覆盖)">
          <el-select v-model="form.mainDrives" multiple clearable :placeholder="`跟随全局${globalMainDrivesLabel}`">
            <el-option v-for="drive in driveOptions" :key="drive.value" :label="driveLabel(drive)" :value="drive.value"/>
          </el-select>
          <span class="sub-text" style="margin-left:8px">巡检保证该盘剧集完整并固定播放线路,选 1-2 个;清空 = 跟随全局</span>
        </el-form-item>
        <el-form-item label="盘类型偏好">
          <el-select v-model="form.driveTypes" multiple placeholder="多选,按优先级排序(候选打分)">
            <el-option v-for="drive in driveOptions" :key="drive.value" :label="driveLabel(drive)" :value="drive.value"/>
          </el-select>
        </el-form-item>
        <el-form-item label="清晰度">
          <el-select v-model="form.qualities" multiple allow-create placeholder="如 4K / 1080P">
            <el-option v-for="q in ['4K', '1080P', '720P']" :key="q" :label="q" :value="q"/>
          </el-select>
          <span class="sub-text">命中加排序分;硬门槛(拒低清)在 追剧设置-资源筛选 全局配置</span>
        </el-form-item>
        <el-form-item label="包含关键词">
          <el-select v-model="form.includeKeywords" multiple allow-create filterable placeholder="字幕组等,回车添加"/>
        </el-form-item>
        <el-form-item label="排除关键词">
          <el-select v-model="form.excludeKeywords" multiple allow-create filterable placeholder="如 预告/枪版,回车添加"/>
        </el-form-item>
        <el-form-item label="单集下限(MB)">
          <el-input-number v-model="form.minEpisodeSizeMb" :min="0" :max="100000"/>
          <span class="sub-text" style="margin-left:8px">0 = 跟随全局({{ globalMinEpisodeSizeLabel }});过滤预告/花絮</span>
        </el-form-item>
        <el-form-item label="单集上限(MB)">
          <el-input-number v-model="form.maxEpisodeSizeMb" :min="0" :max="1000000"/>
          <span class="sub-text" style="margin-left:8px">0 = 跟随全局({{ globalMaxEpisodeSizeLabel }});过滤捆绑包/异常大文件</span>
        </el-form-item>
        <el-collapse style="width:100%">
          <el-collapse-item title="打分权重(高级,留空用默认)">
            <div class="weights-grid">
              <div v-for="def in weightDefs" :key="def.key" class="weights-item">
                <span>{{ def.label }}</span>
                <el-input-number v-model="form.weights[def.key]" :min="-100" :max="100"
                                 :placeholder="String(def.value)" controls-position="right" style="width:110px"/>
              </div>
            </div>
            <div class="sub-text" style="margin-top:6px">
              候选排序偏好:调 0 只是不再优先,不会把候选筛空;清空数值恢复默认。硬过滤(盘类型/关键词/体积)在上方
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button @click="preview" :loading="previewing">预览资源</el-button>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="save" :loading="saving">{{ form.id ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="previewVisible" :title="form.id ? '匹配预览(dry-run,按当前表单条件试算,未保存)' : '匹配预览(dry-run,未订阅)'" width="800" top="3vh">
      <el-table :data="previewItems" border v-loading="previewing" max-height="480">
        <el-table-column prop="title" label="资源" min-width="260" show-overflow-tooltip/>
        <el-table-column prop="drive" label="盘" width="90"/>
        <el-table-column prop="score" label="评分" width="70" sortable/>
        <el-table-column prop="reasons" label="打分明细" min-width="200" show-overflow-tooltip/>
        <el-table-column prop="validity" label="有效性" width="90"/>
      </el-table>
    </el-dialog>

    <el-drawer v-model="resourcesVisible" :title="'候选资源 - ' + (current?.name || '')" size="62%">
      <div class="resources-toolbar">
        <el-button size="small" type="primary" plain @click="openAddResource">添加资源</el-button>
        <el-button size="small" type="primary" plain @click="openManualMagnet">磁力补缺</el-button>
        <span class="sub-text">粘贴分享链接只入候选池,不挂载不动主源;巡检/补缺时自动探测,想立即挂载点「启用」(只挂为补缺源,不动主源;换主源用「转主源」)</span>
      </div>
      <el-table :data="resources" border v-loading="resourcesLoading">
        <el-table-column prop="title" label="资源" min-width="240" show-overflow-tooltip>
          <template #default="scope">
            <!-- 名称即分享链接入口:TG/站点入池的 link 均为可直达的分享地址,href 折入提取码免手输 -->
            <a v-if="scope.row.link?.startsWith('http')" :href="resourceShareLink(scope.row)" target="_blank" rel="noopener"
               class="resource-link">{{ scope.row.title || scope.row.link }}</a>
            <span v-else>{{ scope.row.title || scope.row.link }}</span>
            <el-tag v-if="scope.row.source === 'manual'" size="small" type="info" style="margin-left: 4px">手动</el-tag>
            <span v-if="scope.row.password" class="resource-passcode">提取码 {{ scope.row.password }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="driveName" label="盘" width="90"/>
        <el-table-column prop="score" label="评分" width="70" sortable/>
        <el-table-column label="状态" width="90">
          <template #default="scope">
            <el-tag size="small" :type="stateType(scope.row.state)">{{ stateLabel(scope.row.state) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="episodesFound" label="集数" width="70"/>
        <el-table-column label="单集均大小" width="90">
          <template #default="scope">{{ formatSize(scope.row.avgFileSize) }}</template>
        </el-table-column>
        <el-table-column label="角色" width="90">
          <template #default="scope">
            <el-tag v-if="scope.row.primary" size="small" type="success">主源</el-tag>
            <el-tag v-else-if="scope.row.state === 'MOUNTED'" size="small" type="warning">补缺</el-tag>
            <el-tag v-if="scope.row.pinned" size="small" type="danger" style="margin-left: 4px">钉选</el-tag>
            <el-tag v-if="scope.row.startEpisode" size="small" type="info" style="margin-left: 4px">起{{ scope.row.startEpisode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="320">
          <template #default="scope">
            <el-button v-if="scope.row.state === 'CANDIDATE'" link type="primary" size="small"
                       @click="enableResource(scope.row)">启用</el-button>
            <!-- 转主源一步直达:候选行也要有(线上反馈"不缺的候补无法变主源"——此前只有已挂载补缺行才有此按钮,候选换主源得先启用再转两步,或用语义不直白的钉选) -->
            <el-button v-if="scope.row.state === 'CANDIDATE' || (scope.row.state === 'MOUNTED' && !scope.row.primary)"
                       link type="primary" size="small"
                       @click="activateResource(scope.row)">转主源</el-button>
            <el-button v-if="scope.row.pinned" link type="danger" size="small"
                       @click="unpinResource(scope.row)">取消钉选</el-button>
            <el-button v-else-if="scope.row.state !== 'REMOVED'" link type="danger" size="small"
                       @click="pinResource(scope.row)">钉选</el-button>
            <el-button v-if="scope.row.state !== 'REMOVED'" link type="warning" size="small"
                       @click="setResourceStart(scope.row)">起始集号</el-button>
            <el-button v-if="scope.row.state === 'REMOVED'" link type="primary" size="small"
                       @click="restoreResource(scope.row)">恢复</el-button>
            <el-button v-else-if="!scope.row.primary" link type="danger" size="small"
                       @click="removeResource(scope.row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="addResourceVisible" title="手动添加候选资源" width="560">
      <el-form label-width="70px" @submit.prevent>
        <el-form-item label="分享链接">
          <el-input v-model="addResourceForm.link" type="textarea" :rows="3" placeholder="网盘分享链接(夸克/UC/阿里/百度/115/天翼/移动/123/迅雷/光鸭)"
                    :disabled="addResourceSaving"/>
        </el-form-item>
        <el-form-item label="提取码">
          <el-input v-model="addResourceForm.password" placeholder="无提取码可留空" :disabled="addResourceSaving"/>
        </el-form-item>
      </el-form>
      <div class="sub-text">
        只加入候选池:不挂载、不替换当前主源。巡检补缺/换源时自动探测(候选序置顶);要立即挂载,请在列表点「启用」(只挂为补缺源,不动主源;换主源用「转主源/钉选」)。
      </div>
      <template #footer>
        <el-button @click="addResourceVisible = false">取消</el-button>
        <el-button type="primary" :loading="addResourceSaving" @click="submitAddResource">添加</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="magnetVisible" title="磁力补缺" width="720">
      <div class="magnet-search-bar">
        <el-input v-model="magnetSearchKeyword" placeholder="搜索关键词(留空按剧名)" clearable
                  :disabled="magnetSearching" style="width: 260px" @keyup.enter="searchManualMagnets"/>
        <el-button type="primary" :loading="magnetSearching" @click="searchManualMagnets">搜索</el-button>
        <span class="sub-text">TG-Search + 6V/观影/盘聚/盘链 并发搜索磁力/ed2k(未配置的源自然为空);填了集号会拼进搜索词</span>
      </div>
      <div v-if="magnetResults.length" class="magnet-results">
        <div v-for="(item, i) in magnetResults" :key="i" class="magnet-result-item">
          <div class="magnet-result-head">
            <el-tag size="small" :type="item.type === 'ed2k' ? 'warning' : 'info'">{{ item.type || 'magnet' }}</el-tag>
            <el-tag size="small" effect="plain">{{ item.source }}</el-tag>
            <span class="magnet-result-title" :title="item.title">{{ item.title }}</span>
            <span v-if="item.size" class="sub-text">{{ formatSize(item.size) }}</span>
            <el-button link type="primary" size="small" :loading="magnetResolving === item.link"
                       @click="resolveMagnet(item)">解析</el-button>
            <el-button link type="success" size="small" :loading="magnetSubmittingLink === item.link"
                       @click="submitMagnetItem(item)">入库</el-button>
          </div>
          <div v-if="magnetResolved[item.link]" class="magnet-files">
            <template v-if="magnetResolved[item.link].resolved">
              <div class="sub-text">
                种子 {{ magnetResolved[item.link].name }} · {{ formatSize(magnetResolved[item.link].totalSize) }}
                · {{ magnetResolved[item.link].files.length }} 个文件
              </div>
              <div v-for="(f, j) in magnetResolved[item.link].files" :key="j" class="magnet-file-row">
                <span class="magnet-file-name" :title="f.path">{{ f.path }}</span>
                <span class="sub-text">{{ formatSize(f.size) }}</span>
                <el-tag v-if="f.episode" size="small" type="success">第{{ f.episode }}集</el-tag>
              </div>
            </template>
            <div v-else class="sub-text">{{ magnetResolved[item.link].message || '解析失败' }}</div>
          </div>
        </div>
      </div>
      <el-divider style="margin: 12px 0"/>
      <el-form label-width="70px" @submit.prevent>
        <el-form-item label="磁力链接">
          <el-input v-model="magnetForm.url" type="textarea" :rows="3"
                    placeholder="磁力(magnet:)或 ed2k 链接,也可从上方搜索结果入库" :disabled="magnetSaving"/>
        </el-form-item>
        <el-form-item label="集号">
          <el-input-number v-model="magnetForm.episode" :min="1" :max="9999"
                           placeholder="留空自动识别" :disabled="magnetSaving" style="width: 160px"/>
        </el-form-item>
      </el-form>
      <div class="sub-text">
        提交到全局离线下载账号(网盘账号页「离线下载」配置)补缺失的集,下载完成自动入账并出现在集数清单。
        集号留空按文件名自动识别(整季/多集种子建议留空);提交同步等待最长约 30 秒,超时转为后台等待,
        完成后点「检查更新」或等巡检自动入库。
      </div>
      <template #footer>
        <el-button @click="magnetVisible = false">取消</el-button>
        <el-button type="primary" :loading="magnetSaving" @click="submitManualMagnet">提交</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="episodesVisible" :title="'集数清单 - ' + (current?.name || '')" size="52%">
      <div class="episode-filter">
        <el-radio-group v-model="episodeFilter" size="small">
          <el-radio-button value="all">全部 {{ episodeItems.length }}</el-radio-button>
          <el-radio-button value="present">有源 {{ episodePresentCount }}</el-radio-button>
          <el-radio-button value="missing">缺失 {{ episodeMissingCount }}</el-radio-button>
        </el-radio-group>
        <el-button size="small" type="primary" plain style="margin-left: 12px" @click="openManualMagnet">
          磁力补缺
        </el-button>
      </div>
      <el-table :data="filteredEpisodeItems" border v-loading="episodesLoading" max-height="600" row-key="episode">
        <el-table-column type="expand">
          <template #default="scope">
            <div v-if="scope.row.sources?.length" class="episode-matrix">
              <div v-for="(src, i) in scope.row.sources" :key="i" class="episode-matrix-row">
                <el-tag size="small" :type="src.primary ? 'success' : 'warning'">{{ src.primary ? '主源' : '补缺' }}</el-tag>
                <span class="matrix-title">{{ src.title }}</span>
                <el-tag size="small" :type="matrixStateType(src.state)">{{ matrixStateLabel(src) }}</el-tag>
                <span class="sub-text">{{ src.drive }}</span>
                <span v-if="src.state !== 'TRANSFER'" class="sub-text">取链 成功{{ src.successCount }}/失败{{ src.failCount }}</span>
              </div>
            </div>
            <el-empty v-else description="该集暂无资源行" :image-size="40"/>
          </template>
        </el-table-column>
        <el-table-column prop="episode" label="集" width="70" sortable/>
        <el-table-column label="状态" width="90">
          <template #default="scope">
            <el-tag v-if="scope.row.present" size="small" type="success">已有</el-tag>
            <el-tag v-else-if="scope.row.source" size="small" type="danger">损坏</el-tag>
            <el-tag v-else size="small" type="info">缺失</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源"/>
      </el-table>
    </el-drawer>

    <el-drawer class="media-detail" v-model="detailVisible" :title="'媒体详情 - ' + (current?.name || '')" size="58%">
      <div v-loading="detailLoading">
        <div class="detail-actions">
          <el-button size="small" type="primary" :disabled="!current?.metaProvider" @click="refreshMeta">
            刷新元数据
          </el-button>
          <el-button size="small" @click="checkFromDetail">检查更新</el-button>
          <el-button size="small" type="primary" plain @click="openManualMagnet">磁力补缺</el-button>
          <el-button v-if="current?.status === 'ENDED' && hasNextSeason" size="small" type="warning" plain
                     @click="current && subscribeNextSeason(current)">下一季</el-button>
          <el-button v-if="detailData?.subscription?.mountPath" size="small" link type="primary"
                     @click="browseMount">浏览目录</el-button>
          <span v-if="!current?.metaProvider" class="sub-text">未绑定元数据条目,刷新不可用</span>
        </div>
        <div v-if="detailData" class="detail-hero">
          <div v-if="backdropSources.length" class="detail-backdrop">
            <div v-for="(url, i) in backdropSources" :key="url" class="detail-backdrop-layer"
                 :class="{ active: i === backdropIndex }">
              <el-image :src="url" fit="cover" class="detail-backdrop-img">
                <template #error><div class="detail-backdrop"></div></template>
              </el-image>
            </div>
          </div>
          <div class="detail-head">
            <el-image :src="detailData.media.cover" fit="cover" class="detail-poster">
              <template #error>
                <div class="detail-poster cover-placeholder">{{ (detailData.media.name || current?.name || '?').charAt(0) }}</div>
              </template>
            </el-image>
            <div class="detail-info">
              <div class="detail-title">
                {{ detailData.media.name || current?.name }}
                <span v-if="detailData.media.year" class="sub-text">({{ detailData.media.year }})</span>
                <el-tag size="small" :type="detailData.media.status === 'RETURNING' ? 'success' : detailData.media.status === 'ENDED' ? 'info' : 'warning'">
                  {{ detailData.media.status === 'RETURNING' ? '在播' : detailData.media.status === 'ENDED' ? '已完结' : '状态未知' }}
                </el-tag>
                <el-tag size="small" type="info">第{{ detailData.media.season }}季</el-tag>
                <!-- 源标签即链接:有评分显示"豆瓣 6.8",无评分也显示源名可点(条目入口始终在) -->
                <a v-for="(url, label) in detailData.media.links || {}" :key="label"
                   :href="url" target="_blank" rel="noopener" class="rating-tag-link">
                  <el-tag size="small" type="warning">
                    {{ label }} <template v-if="ratingOfSource(label)"> {{ ratingOfSource(label) }}</template>
                  </el-tag>
                </a>
                <a v-if="!Object.keys(detailData.media.links || {}).length && detailData.media.rating"
                   class="rating-tag-link">
                  <el-tag size="small" type="warning">
                    {{ providerName(detailData.media.provider || '') }} {{ detailData.media.rating }}
                  </el-tag>
                </a>
                <span v-if="!Object.keys(detailData.media.links || {}).length
                  && !Object.keys(detailData.media.ratings || {}).length && !detailData.media.rating"
                      class="sub-text">评分:无</span>
              </div>
              <div v-if="detailData.media.originalName && detailData.media.originalName !== detailData.media.name"
                   class="sub-text">{{ detailData.media.originalName }}</div>
              <div v-if="detailData.media.genres?.length" class="detail-genres">
                <el-tag v-for="genre in detailData.media.genres" :key="genre" size="small" effect="plain">{{ genre }}</el-tag>
              </div>
              <div class="sub-text">
                <template v-if="detailData.media.firstAirDate">首播 {{ detailData.media.firstAirDate }} · </template>
                <template v-if="detailData.media.countries?.length">{{ detailData.media.countries.join(' / ') }} · </template>
                <template v-if="detailData.media.languages?.length">{{ detailData.media.languages.join(' / ') }} · </template>
                已播 {{ detailData.media.airedEpisodes ?? 0 }} / 共 {{ detailData.media.totalEpisodes ?? '?' }} 集
                <template v-if="detailData.media.runtimeMinutes"> · 每集约{{ detailData.media.runtimeMinutes }}分钟</template>
                <template v-if="detailData.media.totalSeasons"> · 全剧{{ detailData.media.totalSeasons }}季</template>
              </div>
              <div class="sub-text">本地已有 {{ detailData.subscription?.currentEpisodes ?? 0 }} 集</div>
              <div v-if="detailData.media.nextAirTime" class="sub-text">下集播出:{{ formatTime(detailData.media.nextAirTime) }}</div>
              <div v-if="detailData.media.directors?.length" class="sub-text">
                导演:{{ detailData.media.directors.join(' / ') }}
              </div>
              <div v-if="detailData.media.writers?.length" class="sub-text">
                编剧:{{ detailData.media.writers.join(' / ') }}
              </div>
              <div v-if="detailData.media.aliases?.length" class="sub-text">别名:{{ detailData.media.aliases.join(' / ') }}</div>
              <div v-if="detailData.media.overview" class="detail-overview">{{ detailData.media.overview }}</div>
            </div>
          </div>
          <div v-if="detailData.media.cast?.length" class="detail-cast">
            <div v-for="(person, i) in detailData.media.cast" :key="i" class="cast-card">
              <el-image :src="person.avatar" fit="cover" class="cast-avatar">
                <template #error>
                  <div class="cast-avatar cast-placeholder">{{ (person.name || '?').charAt(0) }}</div>
                </template>
              </el-image>
              <div class="cast-name">{{ person.name }}</div>
              <div v-if="person.role" class="cast-role">{{ person.role }}</div>
            </div>
          </div>
        </div>
        <el-table :data="detailData?.episodes || []" border max-height="560" row-key="episode">
          <el-table-column type="expand">
            <template #default="scope">
              <div v-if="scope.row.overview || scope.row.still" class="episode-detail">
                <el-image v-if="scope.row.still" :src="scope.row.still" fit="cover" class="episode-still">
                  <template #error><div class="episode-still"></div></template>
                </el-image>
                <span v-if="scope.row.overview">{{ scope.row.overview }}</span>
              </div>
              <el-empty v-else description="暂无分集简介" :image-size="40"/>
            </template>
          </el-table-column>
          <el-table-column prop="episode" label="集" width="70" sortable/>
          <el-table-column label="标题" min-width="170" show-overflow-tooltip>
            <template #default="scope">{{ scope.row.title || '—' }}</template>
          </el-table-column>
          <el-table-column label="播出时间" width="190">
            <template #default="scope">{{ scope.row.airTime ? formatTime(scope.row.airTime) : '—' }}</template>
          </el-table-column>
          <el-table-column label="时长" width="70">
            <template #default="scope">{{ scope.row.runtime ? scope.row.runtime + '分' : '—' }}</template>
          </el-table-column>
          <el-table-column label="播出" width="80">
            <template #default="scope">
              <el-tag v-if="scope.row.aired" size="small" type="info">已播</el-tag>
              <el-tag v-else size="small" type="warning">未播</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="本地" min-width="140">
            <template #default="scope">
              <el-tag v-if="scope.row.present" size="small" type="success">已有</el-tag>
              <el-tag v-else size="small" type="info">缺失</el-tag>
              <span v-if="scope.row.source" class="sub-text" style="margin-left:4px">{{ scope.row.source }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <el-drawer v-model="eventsVisible" :title="'更新动态 - ' + (current?.name || '')" size="45%">
      <div v-loading="eventsLoading" style="min-height: 120px">
      <el-timeline v-if="events.length">
        <el-timeline-item v-for="event in events" :key="event.id" :timestamp="formatTime(event.createdTime)"
                          :type="eventType(event.type)">
          {{ eventDetail(event) }}
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无动态"/>
      </div>
    </el-drawer>

    <el-dialog v-model="importVisible" title="导入订阅" width="600">
      <el-input v-model="importText" type="textarea" :rows="12" placeholder='粘贴导出的 JSON 数组'/>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" @click="importSubs" :loading="importing">导入</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="notifyVisible" title="追剧设置" width="50%">
      <el-form label-width="140">
        <el-tabs v-model="notifyTab">
          <el-tab-pane label="通用" name="general">
            <el-form-item v-if="store.admin" label="全局主网盘">
              <el-select v-model="notifyForm.mainDrives" multiple placeholder="选 1-2 个,按优先级排序" style="width: 100%">
                <el-option v-for="drive in driveOptions" :key="drive.value" :label="driveLabel(drive)" :value="drive.value"/>
              </el-select>
              <span class="sub-text">巡检保证主网盘剧集完整并固定播放线路;订阅可单独覆盖;分享挂载免登录,标注"已加账号"的盘更稳</span>
            </el-form-item>
            <el-form-item v-if="store.admin" label="扩展网盘">
              <el-select v-model="notifyForm.extendedDrives" multiple clearable placeholder="留空时候选仅收主网盘" style="width: 100%">
                <el-option v-for="drive in driveOptions" :key="drive.value" :label="driveLabel(drive)" :value="drive.value"/>
              </el-select>
              <span class="sub-text">主网盘以外允许进候选池的网盘;不配置则候选/补缺/分盘线路只有主网盘的源(主网盘也未配置时才不限盘)</span>
            </el-form-item>
            <el-form-item label="Bot Token">
              <el-input v-model="notifyForm.botToken" placeholder="123456:ABC-...,留空关闭通知"/>
            </el-form-item>
            <el-form-item label="Chat ID">
              <el-input v-model="notifyForm.chatId" placeholder="与 bot 对话后获取"/>
              <span v-if="!store.admin" class="sub-text">个人 TG 通知渠道:留空时沿用管理员配置的全局渠道</span>
            </el-form-item>
            <el-form-item label="免打扰时段">
              <el-input v-model="notifyForm.quietHours" placeholder="23:00-08:00,留空立即发送"/>
              <span class="sub-text">时段内(可跨零点)巡检通知推迟到时段结束合并送达;凌晨巡检不半夜响铃</span>
            </el-form-item>
            <el-form-item v-if="store.admin" label="Bot 交互">
              <el-switch v-model="notifyForm.botEnabled"/>
              <span class="sub-text" style="margin-left:8px">允许在 Telegram 里与 Bot 对话(查订阅/搜索/追剧/退订);只需收通知不需要对话时可关闭</span>
            </el-form-item>
            <el-form-item v-if="store.admin" label="采集源兜底">
              <el-switch v-model="notifyForm.collectionFallback"/>
              <span class="sub-text" style="margin-left:8px">候选源均不可用时,播放前从采集站搜索并补齐缺失剧集(当前集+后3集,直链不改写追剧数据)</span>
            </el-form-item>
            <el-form-item v-if="store.admin" label="完结归档(天)">
              <el-input-number v-model="notifyForm.archiveDays" :min="0" :max="3650"/>
              <span class="sub-text" style="margin-left:8px">完结 N 天后自动释放转存文件,0=关闭</span>
            </el-form-item>
            <el-form-item v-if="store.admin" label="单集磁力配额">
              <el-input-number v-model="notifyForm.magnetEpisodeQuota" :min="0" :max="50"/>
              <span class="sub-text" style="margin-left:8px">同一集的磁力离线提交尝试上限(含失败),0=不限,默认 2;每月1号重置;当前离线下载: {{ offlineAccountLabel }}</span>
            </el-form-item>
            <el-form-item v-if="store.admin" label="单订阅磁力配额">
              <el-input-number v-model="notifyForm.magnetSubscriptionQuota" :min="0" :max="1000"/>
              <span class="sub-text" style="margin-left:8px">单个订阅的磁力离线提交总数上限,0=不限,默认 30;每月1号重置</span>
            </el-form-item>
            <el-form-item v-if="store.admin" label="追剧总磁力配额">
              <el-input-number v-model="notifyForm.magnetTotalQuota" :min="0" :max="10000"/>
              <span class="sub-text" style="margin-left:8px">全部追剧订阅的磁力离线提交总数上限,0=不限,默认 200;每月1号重置</span>
            </el-form-item>
            <el-form-item v-if="store.admin" label="豆瓣 Cookie">
              <el-input v-model="notifyForm.doubanCookie" type="textarea" :rows="2"
                        placeholder="登录 movie.douban.com 后复制 Cookie,留空关闭;用于解析详情页又名/单集播出时间(限速抓取)"/>
              <span class="sub-text">豆瓣条目自动补"又名"提高搜索匹配,并经 IMDb 桥接 TMDB 获取单集播出日程</span>
            </el-form-item>
            <el-form-item v-if="store.admin" label="VIP 账号">
              <el-select v-model="notifyForm.vipAccounts" multiple placeholder="勾选 SVIP/会员账号,资源评分加权" style="width: 100%">
                <el-option v-for="account in accounts" :key="account.id" :label="account.name + '(' + account.type + ')'" :value="account.id"/>
              </el-select>
              <span class="sub-text">对应网盘的候选资源打分 +15(已配置账号本身 +8),如夸克 SVIP/百度 SVIP/115 会员</span>
            </el-form-item>
            <span v-if="store.admin" class="sub-text">玩偶聚合搜索源默认开启无需配置(wanou-enabled 可关);盘聚是项目内命名,实际站点为 SeedHub 系聚合站,免登录无需配置;盘链/观影/蜗牛在各自标签页配置,无凭证的源自动关闭</span>
          </el-tab-pane>
          <el-tab-pane label="资源筛选" name="poolFilter">
            <el-form-item label="清晰度门槛">
              <el-select v-model="notifyForm.poolMinQuality" style="width: 100%">
                <el-option label="不限" value=""/>
                <el-option label="720P 起" value="hd"/>
                <el-option label="1080P 起" value="fhd"/>
                <el-option label="只要 4K" value="uhd"/>
              </el-select>
              <span class="sub-text">仅拒标题明确标注低于门槛的资源;未标注清晰度的放行(挂载前无从判断,避免误杀)</span>
            </el-form-item>
            <el-form-item label="包含关键词">
              <el-select v-model="notifyForm.poolIncludeKeywords" multiple allow-create filterable
                         placeholder="如 国语/中字;回车添加" style="width: 100%"/>
              <span class="sub-text">硬门禁:配置后标题须至少含其一才入池,过严会把候选池筛空;留空不限</span>
            </el-form-item>
            <el-form-item label="排除关键词">
              <el-select v-model="notifyForm.poolExcludeKeywords" multiple allow-create filterable
                         placeholder="如 短剧/枪版/抢先版;回车添加" style="width: 100%"/>
              <span class="sub-text">标题含任一即拒,与订阅级排除词取并集</span>
            </el-form-item>
            <el-form-item label="单集下限(MB)">
              <el-input-number v-model="notifyForm.poolMinEpisodeSizeMb" :min="0" :max="100000"/>
              <span class="sub-text" style="margin-left:8px">0=默认底线;硬底线,低于该体积的集文件直接忽略,过严会丢小体积正片</span>
            </el-form-item>
            <el-form-item label="单集上限(MB)">
              <el-input-number v-model="notifyForm.poolMaxEpisodeSizeMb" :min="0" :max="1000000"/>
              <span class="sub-text" style="margin-left:8px">0=不限,过滤捆绑包/异常大文件;订阅级显式配置优先</span>
            </el-form-item>
            <span class="sub-text">对所有订阅生效(下轮巡检起):入池、存量候选换源、单集文件筛选统一收紧;订阅级单集体积优先、排除词两边并集;已挂载主源不主动更换,自然失效后按新规则换源</span>
          </el-tab-pane>
          <el-tab-pane v-if="store.admin" label="TMDB" name="tmdb">
            <el-form-item label="API Key / Token">
              <el-input v-model="notifyForm.tmdbApiKey" type="password" show-password
                        placeholder="v3 API key(32位)或 v4 read access token(eyJ... 开头);留空用内置公共 key"/>
              <span class="sub-text">两种凭证自动识别:api key 拼请求 URL,read access token 走 Bearer 请求头(不落 URL 与代理访问日志);与 系统设置→TMDB API Key 为同一配置,保存即生效;
                <a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noopener">到官网获取 →</a>
              </span>
            </el-form-item>
            <el-form-item label="TMDB 代理">
              <el-select v-model="notifyForm.tmdbApiHost" style="width: 100%"
                        filterable allow-create default-first-option
                        placeholder="选择预设,或输入自定义 CF Worker 反代地址">
                <el-option v-for="opt in tmdbApiHostOptions" :key="opt.value" :label="opt.label" :value="opt.value"/>
              </el-select>
              <span class="sub-text">可以手动输入代理地址，立即生效;多个地址逗号分隔自动轮询;
                <a href="/tmdb_proxy_worker.js" target="_blank" rel="noopener">自建 Worker 参考代码 →</a>
              </span>
            </el-form-item>
          </el-tab-pane>
          <el-tab-pane v-if="store.admin" label="盘链" name="panlian">
        <el-form-item label="站点">
          <el-input v-model="notifyForm.panlianHost" placeholder="留空用内置地址;自定义镜像站填 https://..."/>
        </el-form-item>
        <el-form-item label="账号">
          <el-input v-model="notifyForm.panlianUsername" placeholder="注册邮箱;账号密码或 Cookie 至少配一样"/>
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="notifyForm.panlianPassword" type="password" show-password placeholder="与账号配套"/>
        </el-form-item>
        <el-form-item label="Cookie">
          <el-input v-model="notifyForm.panlianCookie" type="textarea" :rows="2"
                    placeholder="可代替账号密码:浏览器登录后复制 Cookie;无凭证时该搜索源自动关闭"/>
        </el-form-item>
          </el-tab-pane>
          <el-tab-pane v-if="store.admin" label="观影" name="guanying">
        <el-form-item label="站点">
          <el-input v-model="notifyForm.guanyingHost" placeholder="留空用内置 8 个镜像;多个地址逗号/竖线/换行分隔"/>
        </el-form-item>
        <el-form-item label="账号">
          <el-input v-model="notifyForm.guanyingUsername" placeholder="账号密码或 Cookie 至少配一样"/>
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="notifyForm.guanyingPassword" type="password" show-password placeholder="与账号配套"/>
        </el-form-item>
        <el-form-item label="Cookie">
          <el-input v-model="notifyForm.guanyingCookie" type="textarea" :rows="2"
                    placeholder="可代替账号密码:浏览器登录后复制 Cookie;无凭证时该搜索源自动关闭"/>
        </el-form-item>
          </el-tab-pane>
          <el-tab-pane v-if="store.admin" label="蜗牛" name="woniu">
        <el-form-item label="站点">
          <el-input v-model="notifyForm.woniuHost" placeholder="留空自动测速双线路(wn4k/zmi);自定义填 https://..."/>
        </el-form-item>
        <el-form-item label="账号">
          <el-input v-model="notifyForm.woniuUsername" placeholder="推荐账号密码(Cookie 过期自动续期)"/>
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="notifyForm.woniuPassword" type="password" show-password placeholder="与账号配套"/>
        </el-form-item>
        <el-form-item label="Cookie">
          <el-input v-model="notifyForm.woniuCookie" type="textarea" :rows="2"
                    placeholder="须含 user_check(登录后复制);未登录网盘链接会被打码,无凭证时该搜索源自动关闭"/>
        </el-form-item>
          </el-tab-pane>
          <el-tab-pane v-if="store.admin" label="123臻藏" name="zencang">
            <el-form-item label="站点">
              <el-input v-model="notifyForm.zencangHost" placeholder="留空用内置地址;自定义镜像站填 https://..."/>
            </el-form-item>
            <el-form-item label="Cookie">
              <el-input v-model="notifyForm.zencangCookie" type="textarea" :rows="2"
                        placeholder="浏览器登录 123 云盘·臻藏阁后复制 Cookie(须含 wordpress_logged_in_xxx);正文默认隐藏,无 Cookie 时该搜索源自动关闭"/>
            </el-form-item>
            <span class="sub-text">123 云盘主题资源站;仅订阅的候选盘(主网盘/扩展网盘)包含 123 网盘时才参与搜索</span>
          </el-tab-pane>
          <el-tab-pane v-if="store.admin" label="123社区" name="pan123community">
            <el-form-item label="站点">
              <el-input v-model="notifyForm.pan123communityHost" placeholder="留空自动探活双站点(123panfx/pan1.me);自定义填 https://..."/>
            </el-form-item>
            <el-form-item label="Cookie">
              <el-input v-model="notifyForm.pan123communityCookie" type="textarea" :rows="2"
                        placeholder="可选;须含 bbs_sid 和 bbs_token(浏览器登录 123分享社区后复制)。不配也可匿名搜索,只是「回复后可见」帖自动跳过"/>
            </el-form-item>
            <span class="sub-text">123 云盘分享社区(纯 123 盘产出);仅订阅的候选盘(主网盘/扩展网盘)包含 123 网盘时才参与搜索</span>
          </el-tab-pane>
          <el-tab-pane v-if="store.admin" label="夸父" name="kuafu">
            <el-form-item label="站点">
              <el-input v-model="notifyForm.kuafuHost" placeholder="留空用内置地址 https://www.kfzy.net;自定义填 https://..."/>
            </el-form-item>
            <el-form-item label="Cookie">
              <el-input v-model="notifyForm.kuafuCookie" type="textarea" :rows="2"
                        placeholder="可选;须含 bbs_sid 和 bbs_token(浏览器登录夸父资源社后复制)。不配也能取到公开链接与锁贴泄漏链接,只是「回复后可见」帖自动跳过"/>
            </el-form-item>
            <span class="sub-text">夸父资源社(夸克为主混多盘);仅订阅的候选盘(主网盘/扩展网盘)包含夸克网盘时才参与搜索</span>
          </el-tab-pane>
          <el-tab-pane v-if="store.admin" label="TG-Search" name="tgsearch">
            <el-form-item label="TG-Search地址">
              <el-input v-model="notifyForm.tgSearch" placeholder="http://IP:9900"/>
              <span class="sub-text"><a href="https://github.com/power721/tg-search" target="_blank">部署 TG-Search</a>;与播放设置共用,留空关闭</span>
            </el-form-item>
            <el-form-item label="TG-Search API Key">
              <el-input v-model="notifyForm.tgSearchApiKey" type="password" show-password/>
            </el-form-item>
            <span class="sub-text">追剧巡检的链接有效性检测(候选换源/挂载前探测)按 盘检 tab 的优先级走对应后端</span>
          </el-tab-pane>
          <el-tab-pane v-if="store.admin" label="盘搜" name="pansou">
            <el-form-item label="PanSou地址">
              <el-input v-model="notifyForm.panSouUrl" placeholder="http://IP:8888"/>
              <span class="sub-text">与播放设置-盘搜配置共用;配置后追剧搜索源启用「鱼佬盘搜/盘搜 • 分组」</span>
            </el-form-item>
            <el-form-item label="PanSou用户名" v-if="notifyForm.panSouUrl && panSouAuthEnabled">
              <el-input v-model="notifyForm.panSouUsername"/>
            </el-form-item>
            <el-form-item label="PanSou密码" v-if="notifyForm.panSouUrl && panSouAuthEnabled">
              <el-input v-model="notifyForm.panSouPassword" type="password" show-password/>
            </el-form-item>
            <el-form-item label="PanSou数据源" v-if="notifyForm.panSouUrl">
              <el-radio-group v-model="notifyForm.panSouSource" class="ml-4">
                <el-radio size="large" value="all">全部</el-radio>
                <el-radio size="large" value="tg">电报</el-radio>
                <el-radio size="large" value="plugin">插件</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="PanSou频道列表" v-if="notifyForm.panSouUrl">
              <el-radio-group v-model="notifyForm.panSouChannels" class="ml-4">
                <el-radio size="large" value="custom">自定义</el-radio>
                <el-radio size="large" value="project">项目内置</el-radio>
                <el-radio size="large" value="pansou">盘搜内置</el-radio>
              </el-radio-group>
              <span class="sub-text">自定义=频道管理里勾选的频道,项目内置=本站内置频道清单,盘搜内置=PanSou 自带频道</span>
            </el-form-item>
            <el-form-item label="并发数" v-if="notifyForm.panSouUrl">
              <el-input-number v-model="notifyForm.panSouConc" :min="0" placeholder="自动"/>
              <span class="sub-text" style="margin-left:8px">0=上游自动并发(频道数+插件数+10)</span>
            </el-form-item>
            <el-form-item label="强制刷新" v-if="notifyForm.panSouUrl">
              <el-switch v-model="notifyForm.panSouRefresh"/>
              <span class="sub-text" style="margin-left:8px">跳过 PanSou 缓存,获取最新数据</span>
            </el-form-item>
            <el-form-item label="包含词" v-if="notifyForm.panSouUrl">
              <el-input v-model="notifyForm.panSouFilterInclude" placeholder="多个用逗号分隔,如 1080,4K"/>
            </el-form-item>
            <el-form-item label="排除词" v-if="notifyForm.panSouUrl">
              <el-input v-model="notifyForm.panSouFilterExclude" placeholder="多个用逗号分隔,如 枪版,广告"/>
            </el-form-item>
            <span class="sub-text">地址留空=关闭盘搜源;是否需要用户名密码登录由 PanSou /api/health 返回的鉴权状态决定;数据源/频道列表/并发/刷新/包含词/排除词与播放设置完全同步</span>
          </el-tab-pane>
          <el-tab-pane v-if="store.admin" label="盘检" name="pancheck">
            <el-form-item label="盘检地址">
              <el-input v-model="notifyForm.panCheckUrl" placeholder="http://IP:6080"/>
              <span class="sub-text"><a href="https://github.com/Lampon/PanCheck" target="_blank">部署 PanCheck</a>;独立网盘链接检测后端,配置后优先使用;优先级:盘检地址 &gt; TG-Search &gt; PanSou,留空则回退</span>
            </el-form-item>
            <el-form-item label="盘检超时(ms)">
              <el-input-number v-model="notifyForm.panCheckTimeoutMs" :min="0" :step="1000" placeholder="默认5000"/>
              <span class="sub-text" style="margin-left:8px">仅在走 TG-Search 盘检时作为 timeout_ms 生效,0=上游默认(PanCheck/PanSou 无此参数)</span>
            </el-form-item>
            <el-form-item label="链接检测">
              <el-switch v-model="notifyForm.panSouLinkCheckEnabled"/>
              <span class="sub-text" style="margin-left:8px">自动检查盘搜搜索结果的有效性</span>
            </el-form-item>
            <el-form-item label="检测网盘类型">
              <el-checkbox-group v-model="notifyForm.panSouLinkCheckTypes">
                <el-checkbox v-for="t in panSouLinkCheckTypeOptions" :key="t.value" :label="t.label" :value="t.value"/>
              </el-checkbox-group>
              <span class="sub-text">留空=检测全部9种</span>
            </el-form-item>
            <el-form-item label="检测数量上限">
              <el-input-number v-model="notifyForm.panSouLinkCheckMaxCount" :min="0" :max="1000"/>
              <span class="sub-text" style="margin-left:8px">仅当网盘结果数量小于等于该值时检查,磁力和ED2K不计算数量</span>
            </el-form-item>
            <span class="sub-text">追剧巡检的链接有效性检测(候选换源/挂载前探测)走这里配置的后端</span>
          </el-tab-pane>
        </el-tabs>
      </el-form>
      <template #footer>
        <el-button @click="notifyVisible = false">取消</el-button>
        <el-button type="primary" :loading="notifySaving" :disabled="!notifyLoaded" @click="saveNotify">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="navigationVisible" title="片单追更(豆瓣/TMDB 热门榜单选剧订阅)" width="960" top="3vh">
      <div class="nav-toolbar">
        <el-select v-model="navType" filterable style="width: 240px" @change="onNavTypeChange">
          <el-option v-for="item in navCategories" :key="item.type_id" :label="item.type_name" :value="item.type_id"/>
        </el-select>
        <el-select v-for="f in navFilterDefs" :key="f.key" v-model="navFilters[f.key]" :placeholder="f.name"
                   clearable style="width: 132px" @change="onNavFilterChange">
          <el-option v-for="option in f.value" :key="option.v" :label="option.n" :value="option.v"/>
        </el-select>
        <span class="sub-text">共 {{ navTotal }} 条 · 点击追更补充季/网盘等信息:TMDB 条目自动绑定元数据,豆瓣条目自动匹配条目</span>
      </div>
      <div class="nav-grid" v-loading="navLoading">
        <div v-for="item in navList" :key="item.vod_id" class="nav-card">
          <el-image :src="item.vod_pic" fit="cover" class="nav-cover cover-click" lazy @click="showNavDetail(item)">
            <template #error><div class="nav-cover nav-cover-placeholder cover-click" @click="showNavDetail(item)">{{ (item.vod_name || '?').charAt(0) }}</div></template>
          </el-image>
          <div class="nav-title name-link" :title="item.vod_name" @click="showNavDetail(item)">{{ item.vod_name }}</div>
          <div class="nav-meta">
            <span v-if="item.vod_remarks">{{ item.vod_remarks }}</span>
            <span v-if="item.vod_year">{{ item.vod_year }}</span>
            <span v-if="item.type_name">{{ item.type_name }}</span>
          </div>
          <el-button v-if="isNavSubscribed(item)" size="small" disabled>已追更</el-button>
          <el-button v-else size="small" type="primary" @click="navSubscribe(item)">追更</el-button>
        </div>
      </div>
      <div class="nav-pager" v-if="navPageCount > 1">
        <el-pagination background layout="prev, pager, next" :total="navTotal" :page-size="24"
                       :current-page="navPage" @current-change="onNavPageChange"/>
      </div>
    </el-dialog>

    <el-dialog v-model="navDetailVisible" title="媒体详情" width="680">
      <div class="nav-detail" v-loading="navDetailLoading">
        <template v-if="navDetail">
          <el-image :src="navDetailPoster" fit="cover" class="nav-detail-poster">
            <template #error>
              <div class="nav-detail-poster cover-placeholder">{{ (navDetail.vod_name || '?').charAt(0) }}</div>
            </template>
          </el-image>
          <div class="nav-detail-info">
            <div class="nav-detail-title">
              {{ navDetail.vod_name }}
              <span v-if="navDetail.vod_year" class="sub-text">({{ navDetail.vod_year }})</span>
            </div>
            <div class="nav-detail-tags">
              <el-tag v-for="genre in navDetailGenres" :key="genre" size="small" effect="plain">{{ genre }}</el-tag>
              <el-tag v-if="navDetail.vod_remarks" size="small" type="warning">{{ navDetail.vod_remarks }}</el-tag>
              <el-tag v-for="season in navDetailSeasons" :key="'s' + season" size="small" type="info">第{{ season }}季</el-tag>
            </div>
            <div v-if="navDetail.vod_director" class="sub-text">导演:{{ navDetail.vod_director }}</div>
            <div v-if="navDetail.vod_actor" class="sub-text">演员:{{ navDetail.vod_actor }}</div>
            <div v-if="navDetail.vod_area || navDetail.vod_lang" class="sub-text">
              <template v-if="navDetail.vod_area">{{ navDetail.vod_area }}</template>
              <template v-if="navDetail.vod_area && navDetail.vod_lang"> / </template>
              <template v-if="navDetail.vod_lang">{{ navDetail.vod_lang }}</template>
            </div>
            <div v-if="navDetail.vod_content" class="nav-detail-overview">{{ navDetail.vod_content }}</div>
            <div v-else class="sub-text">暂无简介,点击「追更」按标题订阅</div>
          </div>
        </template>
      </div>
      <template #footer>
        <el-button @click="navDetailVisible = false">关闭</el-button>
        <el-button v-if="navDetailItem && isNavSubscribed(navDetailItem)" disabled>已追更</el-button>
        <el-button v-else type="primary" @click="navDetailSubscribe">追更</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {computed, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {useRouter} from 'vue-router'
import axios from 'axios'
import {ElMessage, ElMessageBox} from 'element-plus'
import {store} from '@/services/store'

/** 组件卸载统一清理延时刷新(检查/转存后的自动 reload):离开页面后不再触发孤儿请求 */
const pendingTimers = new Set<number>()
const schedule = (fn: () => void, ms: number) => {
  const id = window.setTimeout(() => {
    pendingTimers.delete(id)
    fn()
  }, ms)
  pendingTimers.add(id)
}
onBeforeUnmount(() => pendingTimers.forEach(clearTimeout))

/** 抽屉请求序号:快速切换订阅时旧响应后到不得覆盖新状态 */
let detailSeq = 0
let episodesSeq = 0
let eventsSeq = 0
let resourcesSeq = 0
let navSeq = 0

const router = useRouter()

interface SubscriptionDto {
  id: number
  name: string
  mainDrives: number[] | null
  keyword: string
  customKeywords: string | null
  season: number | null
  seasonStartEpisode: number | null
  doubanId: number | null
  metaProvider: string | null
  metaId: string | null
  officialEpisodes: number | null
  officialTotal: number | null
  officialStatus: string | null
  nextAirTime: number | null
  cover: string | null
  mode: string
  status: string
  accountId: number | null
  accountIds: string[] | null
  expectedEpisodes: number | null
  manualTotalEpisodes: number | null
  currentEpisodes: number | null
  maxEpisode: number | null
  missingEpisodes: number[]
  stallCount: number
  checkIntervalHours: number | null
  customAirClock: string | null
  airWeekdays: number[] | null
  nextCheckTime: number | null
  lastCheckTime: number | null
  resourceCount: number
  gapCount: number
  activeResourceTitle: string | null
  mountPath: string | null
  crossDrive: boolean
  magnetOffline: boolean
  filter: Filter | null
}

interface Filter {
  driveTypes: number[] | null
  qualities: string[] | null
  includeKeywords: string[] | null
  excludeKeywords: string[] | null
  minEpisodeSizeMb: number | null
  maxEpisodeSizeMb: number | null
  /** 打分权重表(维度 key → 加减分);空 = 用后端默认值 */
  weights: Record<string, number> | null
}

interface ResourceDto {
  id: number
  link: string
  /** 分享提取码(有提取码的网盘分享打开时需要) */
  password: string | null
  type: number | null
  driveName: string | null
  title: string | null
  episodesFound: number | null
  /** 单集平均文件大小(字节,未探测过为 null) */
  avgFileSize: number | null
  score: number | null
  /** 挂载生命周期:CANDIDATE/MOUNTED/RETIRED/REJECTED(可用性由集源行聚合,不再落在资源上) */
  state: string | null
  /** 入池来源:manual = 用户手动粘贴链接(豁免盘白名单/年份/标题等自动门禁) */
  source: string | null
  primary: boolean
  /** 手动钉选:换源候选序置顶、归属复核豁免(用户否决自动换源) */
  pinned: boolean
  /** 资源级起始集号:该资源第 1 集对应全剧第 N 集(null = 不平移) */
  startEpisode: number | null
}

interface EventDto {
  id: number
  type: string
  detail: string | null
  createdTime: number
}

/** 媒体详情(/detail):元数据快照 + 分集(标题/播出时间/剧照/简介 + 本地是否已有) */
interface MediaDetailData {
  subscription: SubscriptionDto
  media: {
    provider: string | null
    season: number
    name?: string
    originalName?: string | null
    year?: string
    cover?: string
    backdrop?: string | null
    backdrops?: string[] | null
    status?: string
    totalSeasons?: number
    runtimeMinutes?: number
    overview?: string
    aliases?: string[]
    genres?: string[]
    countries?: string[]
    languages?: string[]
    firstAirDate?: string | null
    rating?: string | null
    ratings?: Record<string, string> | null
    links?: Record<string, string> | null
    directors?: string[]
    writers?: string[]
    cast?: { name: string, role: string | null, avatar: string | null }[]
    officialEpisodes?: number | null
    officialTotal?: number | null
    officialStatus?: string | null
    nextAirTime?: number | null
    totalEpisodes: number
    airedEpisodes: number
  }
  episodes: {
    episode: number
    title: string | null
    airTime: number | null
    aired: boolean
    runtime?: number | null
    present: boolean
    source: string
    overview?: string
    still?: string
  }[]
}

const driveOptions = [
  {value: 5, label: '夸克'},
  {value: 8, label: '115'},
  {value: 0, label: '阿里'},
  {value: 7, label: 'UC'},
  {value: 9, label: '天翼'},
  {value: 10, label: '百度'},
  {value: 3, label: '123云盘'},
  {value: 2, label: '迅雷'},
  {value: 1, label: 'PikPak'},
  {value: 12, label: '光鸭'},
]

// DriverType 枚举名 → 分享类型码(driveOptions 同一命名空间)
const driverTypeCodes: Record<string, number> = {
  QUARK: 5, QUARK_TV: 5, PAN115: 8, OPEN115: 8, ALI: 0, UC: 7, UC_TV: 7,
  CLOUD189: 9, BAIDU: 10, PAN123: 3, OPEN123: 3, THUNDER: 2, GUANGYA: 12, PAN139: 6,
}

const aliAccounts = ref<any[]>([])
const pikpakAccountCount = ref(0)

/** 与 enableMyAli 同规则:showMyAli/master 或唯一账号才挂载了"我的阿里云盘"(可转存) */
const aliSelectable = (account: any) =>
    account.showMyAli || account.master || aliAccounts.value.length === 1

const accountDriveCodes = () => {
  const codes = new Set<number>()
  for (const account of accounts.value || []) {
    const code = driverTypeCodes[(account as any).type]
    if (code !== undefined) codes.add(code)
  }
  if (aliAccounts.value.length > 0) codes.add(0) // 阿里账号在独立表(/api/ali/accounts)
  if (pikpakAccountCount.value > 0) codes.add(1) // PikPak 账号在独立表(/api/pikpak/accounts)
  return codes
}

const driveLabel = (drive: { value: number, label: string }) =>
    accountDriveCodes().has(drive.value) ? `${drive.label}(已加账号)` : drive.label

const globalMainDrives = ref<number[]>([])

const loadGlobalMainDrives = () => {
  axios.get('/api/settings').then(response => {
    const raw = (response.data || {})['msub_main_drives'] || ''
    globalMainDrives.value = raw.split(',').map((v: string) => parseInt(v.trim()))
        .filter((v: number) => v > 0).slice(0, 2)
  }).catch(() => {
  })
}

const globalMainDrivesLabel = computed(() => globalMainDrives.value.length
    ? `(${globalMainDrives.value.map(code => driveOptions.find(d => d.value === code)?.label || code).join('/')})`
    : '(未配置)')

// 全局单集体积上下限(订阅表单「0 = 跟随全局」的展示值):管理员读全局 msub_pool_filter,
// 普通用户读用户级设置(后端读取回退全局),均未配置时后端兜底(下限 20MB/上限不限)
const globalPoolMinEpisodeSizeMb = ref(0)
const globalPoolMaxEpisodeSizeMb = ref(0)

const loadGlobalPoolFilter = () => {
  const apply = (raw: string) => {
    const poolFilter = parsePoolFilter(raw || '')
    globalPoolMinEpisodeSizeMb.value = poolFilter.minEpisodeSizeMb || 0
    globalPoolMaxEpisodeSizeMb.value = poolFilter.maxEpisodeSizeMb || 0
  }
  if (store.admin) {
    axios.get('/api/settings').then(response => {
      apply((response.data || {})['msub_pool_filter'] || '')
    }).catch(() => {
    })
  } else {
    axios.get('/api/user-settings/msub_pool_filter').then(response => {
      apply(response.data?.value || '')
    }).catch(() => {
    })
  }
}

const globalMinEpisodeSizeLabel = computed(() => globalPoolMinEpisodeSizeMb.value > 0
    ? `当前 ${globalPoolMinEpisodeSizeMb.value}MB`
    : '未配置时默认 20MB')

const globalMaxEpisodeSizeLabel = computed(() => globalPoolMaxEpisodeSizeMb.value > 0
    ? `当前 ${globalPoolMaxEpisodeSizeMb.value}MB`
    : '未配置时不限')

const subscriptions = ref<SubscriptionDto[]>([])
// 状态筛选(100+ 订阅规模):列表页按状态收敛;全选/批量操作天然只作用于过滤后的可见行
const statusFilter = ref('')
const filteredSubscriptions = computed(() =>
  statusFilter.value ? subscriptions.value.filter(s => s.status === statusFilter.value) : subscriptions.value)
const stats = ref<any>(null)
const inboxItems = ref<any[]>([])
const inboxExpanded = ref(false)
const scheduleDays = ref<any[]>([])
const loading = ref(false)
const tableRef = ref<any>(null)
const selected = ref<any[]>([])
const formVisible = ref(false)
const saving = ref(false)
const form = ref<any>({})
// 编辑前的原季号:保存时季号有变要确认换季重置(旧季挂载/进度清空,按新季重搜)
const originalSeason = ref<number | null>(null)
/** 磁力兜底提示行:当前离线下载配置的账号(未配置/未开启给出可行动文案);非 admin 保持默认不展示 */
const offlineAccountLabel = ref('加载中...')
const loadOfflineAccountLabel = () => {
  if (!store.admin) {
    offlineAccountLabel.value = ''
    return
  }
  axios.get('/api/offline_download/config').then(({data}) => {
    offlineAccountLabel.value = data?.enabled
        ? `${data.accountName || ('#' + data.accountId)}(${data.folder || data.driverType})`
        : '未开启,需先在网盘账号配置的「离线下载」页签开启并选择账号'
  }).catch(() => {
    offlineAccountLabel.value = ''
  })
}
const metaProvider = ref('tmdb')
const metaKeyword = ref('')
const metaSearching = ref(false)
const metaResults = ref<any[]>([])
const metaLink = ref('')
const resolvingLink = ref(false)
const accounts = ref<any[]>([])
/** 仅中转目标无服务端转存能力(与后端 resolveTargets 的 relayOnly 同口径):不列入转存网盘;历史已选中的保留显示便于取消 */
const relayOnlyTypes = new Set(['QUARK_TV', 'UC_TV', 'OPEN115'])
const transferAccounts = computed(() =>
    (accounts.value || []).filter((account: any) =>
        !relayOnlyTypes.has(account.type) || (form.value.accountIds || []).includes('pan:' + account.id)))
const previewVisible = ref(false)
const previewing = ref(false)
const previewItems = ref<any[]>([])
const eventsLoading = ref(false)
const notifyLoaded = ref(false)
const notifySaving = ref(false)
const resourcesVisible = ref(false)
const resourcesLoading = ref(false)
const resources = ref<ResourceDto[]>([])
// 手动添加候选资源:只入候选池不挂载不动主源(与「转主源/钉选」的换主源分开)
const addResourceVisible = ref(false)
const addResourceSaving = ref(false)
const addResourceForm = ref({link: '', password: ''})
// 手动磁力补缺:贴磁力/ed2k 提交全局离线下载账号补缺集(集号留空按文件名自动识别);
// 搜索区按订阅关键词搜候选(TG-Search,与自动兜底同源),结果可解析看包内容、可入库提交
const magnetVisible = ref(false)
const magnetSaving = ref(false)
const magnetForm = ref<{ url: string; episode: number | null }>({url: '', episode: null})
const magnetSearchKeyword = ref('')
const magnetSearching = ref(false)
const magnetResults = ref<any[]>([])
const magnetResolved = ref<Record<string, any>>({})
const magnetResolving = ref<string | null>(null)
const magnetSubmittingLink = ref<string | null>(null)
const episodesVisible = ref(false)
const episodesLoading = ref(false)
const episodeItems = ref<any[]>([])
const episodeFilter = ref<'all' | 'present' | 'missing'>('all')
const episodePresentCount = computed(() => episodeItems.value.filter(it => it.present).length)
const episodeMissingCount = computed(() => episodeItems.value.length - episodePresentCount.value)
const filteredEpisodeItems = computed(() => episodeFilter.value === 'all' ? episodeItems.value
    : episodeItems.value.filter(it => !!it.present === (episodeFilter.value === 'present')))
const eventsVisible = ref(false)
const events = ref<EventDto[]>([])
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailData = ref<MediaDetailData | null>(null)
// 背景图轮播(atv-player 同款节奏):候选 = backdrops 列表 + 主图兜底去重,后端已升级 original 高清
const backdropIndex = ref(0)
let backdropTimer: number | undefined
const backdropSources = computed(() => {
  const media = detailData.value?.media
  if (!media) return []
  const urls = [...(media.backdrops || []), ...(media.backdrop ? [media.backdrop] : [])]
  return [...new Set(urls)].filter(Boolean)
})
watch([detailVisible, backdropSources], ([visible, sources]) => {
  window.clearInterval(backdropTimer)
  backdropIndex.value = 0
  if (visible && sources.length > 1) {
    sources.slice(1).forEach(url => { const img = new Image(); img.src = url }) // 预加载后续帧,切换不闪白
    backdropTimer = window.setInterval(() => {
      backdropIndex.value = (backdropIndex.value + 1) % sources.length
    }, 4500)
  }
})
onBeforeUnmount(() => window.clearInterval(backdropTimer))
const current = ref<SubscriptionDto | null>(null)
// 元数据总季数已知且当前季已是最后一季 → 不可能有下一季,隐藏按钮(三集迷你剧完结后曾对 1 季条目展示死按钮);
// 总季数未知(无元数据/未拉到)保留入口,点击后后端 next-season 接口会再探测
const hasNextSeason = computed(() => {
  const totalSeasons = detailData.value?.media?.totalSeasons
  if (!totalSeasons) return true
  return totalSeasons > (current.value?.season ?? 1)
})
const importVisible = ref(false)
const importText = ref('')
const importing = ref(false)
const notifyVisible = ref(false)
const notifyTab = ref('general')
// PanSou 是否开启鉴权:由 /api/pansou(健康检查缓存)返回的 auth_enabled 决定,决定是否展示用户名/密码
const panSouAuthEnabled = ref(false)
const loadPanSouAuth = () => {
  axios.get('/api/pansou').then(({data}) => {
    panSouAuthEnabled.value = data.auth_enabled === true
  }).catch(() => {
    panSouAuthEnabled.value = false
  })
}
const notifyForm = ref({
  botToken: '',
  chatId: '',
  quietHours: '',
  botEnabled: true,
  collectionFallback: false,
  doubanCookie: '',
  archiveDays: 0,
  magnetEpisodeQuota: 2,
  magnetSubscriptionQuota: 30,
  magnetTotalQuota: 200,
  tmdbApiKey: '',
  tmdbApiHost: '',
  vipAccounts: [] as number[],
  mainDrives: [] as number[],
  extendedDrives: [] as number[],
  poolMinQuality: '',
  poolIncludeKeywords: [] as string[],
  poolExcludeKeywords: [] as string[],
  poolMinEpisodeSizeMb: 0,
  poolMaxEpisodeSizeMb: 0,
  panlianHost: '',
  panlianUsername: '',
  panlianPassword: '',
  panlianCookie: '',
  guanyingHost: '',
  guanyingUsername: '',
  guanyingPassword: '',
  guanyingCookie: '',
  woniuHost: '',
  woniuUsername: '',
  woniuPassword: '',
  woniuCookie: '',
  zencangHost: '',
  zencangCookie: '',
  pan123communityHost: '',
  pan123communityCookie: '',
  kuafuHost: '',
  kuafuCookie: '',
  panSouUrl: '',
  panSouUsername: '',
  panSouPassword: '',
  panSouSource: 'all',
  panSouChannels: 'custom',
  panSouConc: null as number | null,
  panSouRefresh: false,
  panSouFilterInclude: '',
  panSouFilterExclude: '',
  tgSearch: '',
  tgSearchApiKey: '',
  panCheckUrl: '',
  panCheckTimeoutMs: null as number | null,
  panSouLinkCheckEnabled: false,
  panSouLinkCheckTypes: [] as string[],
  panSouLinkCheckMaxCount: 300,
})
const panSouLinkCheckTypeOptions = [
  {label: '百度网盘', value: 'baidu'},
  {label: '阿里云盘', value: 'aliyun'},
  {label: '夸克网盘', value: 'quark'},
  {label: '天翼云盘', value: 'tianyi'},
  {label: 'UC网盘', value: 'uc'},
  {label: '移动云盘', value: 'mobile'},
  {label: '115网盘', value: '115'},
  {label: '迅雷网盘', value: 'xunlei'},
  {label: '123网盘', value: '123'},
]
const tmdbApiHostOptions = [
  {label: '官方 API(直连)', value: ''},
  {label: 'Worker 轮询池 - round robin', value: 'worker-pool'},
  {label: 'itv666 代理', value: 'http://tmdb.itv666.cc'},
  {label: 'NAStool 代理', value: 'https://tmdb.nastool.org'},
]
const navigationVisible = ref(false)
const navCategories = ref<{ type_id: string, type_name: string }[]>([])
const navAllFilters = ref<Record<string, any[]>>({})
const navFilterDefs = ref<any[]>([])
const navFilters = ref<Record<string, string>>({})
const navType = ref('douban:hot_tv')
const navList = ref<any[]>([])
const navPage = ref(1)
const navPageCount = ref(1)
const navTotal = ref(0)
const navLoading = ref(false)
const navSubscribed = ref<Set<string>>(new Set())
/** 从片单追更打开新建对话框的条目:创建成功后标记"已追更",对话框关闭即解除 */
const navPending = ref<any>(null)

/** 片单条目媒体详情:打开即用榜单卡片数据垫底,后端详情(TMDB 直取/豆瓣本地库富化)回来整体替换 */
const navDetailVisible = ref(false)
const navDetailLoading = ref(false)
const navDetail = ref<any>(null)
const navDetailItem = ref<any>(null)
let navDetailSeq = 0

const navDetailPoster = computed(() => navDetail.value?.vod_pic || navDetailItem.value?.vod_pic || '')
/** 类型(type_name)各源分隔符不一(TMDB " / "、豆瓣逗号),统一拆成 tag 列表 */
const navDetailGenres = computed(() => {
  const source = navDetail.value?.type_name || navDetailItem.value?.type_name || ''
  return String(source).split(/[/,、]/).map((s: string) => s.trim()).filter(Boolean)
})
/** TMDB 剧集季号清单(ext 数组,已滤特典与未开播占位季);电影/豆瓣条目为空 */
const navDetailSeasons = computed(() => Array.isArray(navDetail.value?.ext) ? navDetail.value.ext : [])

const showNavDetail = (item: any) => {
  navDetailItem.value = item
  navDetail.value = {vod_name: item.vod_name, vod_pic: item.vod_pic, vod_year: item.vod_year,
    type_name: item.type_name, vod_remarks: item.vod_remarks}
  navDetailVisible.value = true
  navDetailLoading.value = true
  const my = ++navDetailSeq
  axios.get('/api/media-subscriptions/navigation/detail', {params: {id: item.vod_id}}).then(response => {
    if (my !== navDetailSeq) return
    navDetail.value = response.data || null
  }).catch(() => ElMessage.error('媒体详情加载失败')).finally(() => {
    if (my === navDetailSeq) navDetailLoading.value = false
  })
}

const navDetailSubscribe = () => {
  navDetailVisible.value = false
  if (navDetailItem.value) navSubscribe(navDetailItem.value)
}

onMounted(() => {
  loadAll()
  loadGlobalMainDrives()
  loadGlobalPoolFilter()
  axios.get('/api/pan/accounts').then(response => {
    accounts.value = response.data || []
  }).catch(() => {
  })
  // 阿里/PikPak 账号在各自独立的表,不计入 /api/pan/accounts
  axios.get('/api/ali/accounts').then(response => {
    aliAccounts.value = response.data || []
  }).catch(() => {
  })
  axios.get('/api/pikpak/accounts').then(response => {
    pikpakAccountCount.value = (response.data || []).length
  }).catch(() => {
  })
})

// ---------- 片单追更(csp_PianDan 片单导航榜单选剧,预填新建订阅对话框) ----------

const openNavigation = () => {
  navigationVisible.value = true
  if (!navCategories.value.length) {
    axios.get('/api/media-subscriptions/navigation').then(response => {
      // CategoryList 的分类字段经 @JsonProperty 序列化为 "class"
      navCategories.value = ((response.data['class'] || []) as any[]).filter((c: any) => c.type_id && c.type_id !== '0')
      navAllFilters.value = response.data.filters || {}
      if (!navCategories.value.some(c => c.type_id === navType.value)) {
        navType.value = navCategories.value[0]?.type_id || ''
      }
      applyNavFilters()
      loadNavList()
    }).catch(() => ElMessage.error('片单分类加载失败'))
  }
}

/** 分类切换:换用该分类的筛选定义(地区/年代/排序等,TVBox filter 同源),已选筛选清空。 */
const applyNavFilters = () => {
  navFilterDefs.value = navAllFilters.value[navType.value] || []
  navFilters.value = {}
}

const onNavTypeChange = () => {
  navPage.value = 1
  applyNavFilters()
  loadNavList()
}

const onNavFilterChange = () => {
  navPage.value = 1
  loadNavList()
}

const onNavPageChange = (page: number) => {
  navPage.value = page
  loadNavList()
}

const loadNavList = () => {
  if (!navType.value) return
  navLoading.value = true
  const params: any = {t: navType.value, pg: navPage.value, size: 24}
  Object.entries(navFilters.value).forEach(([key, value]) => {
    if (value) {
      params[key] = value // 空串 = "全部"选项,不传参
    }
  })
  const my = ++navSeq
  axios.get('/api/media-subscriptions/navigation/list', {params}).then(response => {
    if (my !== navSeq) return
    const data = response.data || {}
    navList.value = data.list || []
    navPageCount.value = data.pagecount || 1
    navTotal.value = data.total || navList.value.length
  }).catch(() => ElMessage.error('片单加载失败,该分类可能依赖外部接口')).finally(() => {
    if (my === navSeq) navLoading.value = false
  })
}

const isNavSubscribed = (item: any) => {
  return navSubscribed.value.has(item.vod_name) || subscriptions.value.some(s => s.name === item.vod_name)
}

/** 追更按钮 → 打开新建订阅对话框预填榜单条目,由用户补充(季/网盘/过滤等)后确认创建 */
const navSubscribe = (item: any) => {
  // season 只是"未标注季号时"的默认值:榜单条目名常带季号(如"诛仙 第四季"),
  // 后端 create() 会用 TextUtils.resolveSeason 从名称改写它 —— 此处不再自行判定季号。
  handleAdd()
  form.value.name = item.vod_name
  form.value.keyword = item.vod_name
  metaKeyword.value = item.vod_name
  navPending.value = item
  const vodId = String(item.vod_id || '')
  if (vodId.startsWith('tmdb:')) {
    // tmdb:tv:{id} / tmdb:movie:{id}:绑定元数据,官方集数/播出日程驱动追更;
    // 缺标识时不绑定,用户在对话框按标题提交即纯标题订阅
    const metaId = vodId.split(':')[2]
    if (metaId) {
      form.value.metaProvider = 'tmdb'
      form.value.metaId = metaId
    }
    return
  }
  // 豆瓣条目:榜单 API 不带 subject id,按标题预搜 suggest 自动选中严格匹配项
  // (名称相等+年份一致,防同名翻拍误绑);结果同步列出供用户改选/换源,留空提交则纯标题订阅
  metaProvider.value = 'douban'
  metaSearching.value = true
  axios.get('/api/media-subscriptions/meta/search', {params: {keyword: item.vod_name, provider: 'douban'}})
      .then(response => {
        metaResults.value = response.data.items || []
        const hit = metaResults.value.find((m: any) =>
            m.provider === 'douban' && m.name === item.vod_name
            && (!item.vod_year || !m.year || m.year === item.vod_year))
        if (hit && /^\d+$/.test(String(hit.id))) {
          form.value.metaProvider = 'douban'
          form.value.metaId = String(hit.id)
          form.value.doubanId = Number(hit.id)
        }
      }).catch(() => {
      }).finally(() => {
        metaSearching.value = false
      })
}

const loadAll = () => {
  loading.value = true
  axios.get('/api/media-subscriptions').then(response => {
    subscriptions.value = response.data
  }).finally(() => {
    loading.value = false
  })
  axios.get('/api/media-subscriptions/stats').then(response => {
    stats.value = response.data
  })
  axios.get('/api/media-subscriptions/inbox').then(response => {
    inboxItems.value = response.data || []
  })
  axios.get('/api/media-subscriptions/schedule').then(response => {
    scheduleDays.value = response.data || []
  })
}

const handleAdd = () => {
  originalSeason.value = null
  loadOfflineAccountLabel()
  form.value = {
    name: '',
    keyword: '',
    customKeywords: [] as string[],
    season: 1,
    seasonStartEpisode: null,
    doubanId: null,
    metaProvider: null,
    metaId: null,
    expectedEpisodes: null,
    manualTotalEpisodes: null,
    mode: 'FOLLOW',
    accountId: null,
    accountIds: [] as string[],
    crossDrive: false,
    magnetOffline: false,
    checkIntervalHours: 6,
    customAirClock: null,
    airWeekdays: [] as number[],
    mainDrives: [] as number[],
    driveTypes: [],
    qualities: [],
    includeKeywords: [],
    excludeKeywords: [],
    minEpisodeSizeMb: 0,
    maxEpisodeSizeMb: 0,
    weights: {} as Record<string, number | null>,
  }
  metaKeyword.value = ''
  metaResults.value = []
  metaProvider.value = 'tmdb' // 新建对话框不沿用上次编辑遗留的 tab/链接
  metaLink.value = ''
  formVisible.value = true
}

const handleEdit = (row: SubscriptionDto) => {
  loadOfflineAccountLabel()
  originalSeason.value = row.season ?? 1
  form.value = {
    id: row.id,
    name: row.name,
    keyword: row.keyword,
    customKeywords: (row.customKeywords || '').split('\n').map((s: string) => s.trim()).filter(Boolean),
    season: row.season ?? 1,
    seasonStartEpisode: row.seasonStartEpisode ?? null,
    doubanId: row.doubanId,
    metaProvider: row.metaProvider,
    metaId: row.metaId,
    expectedEpisodes: row.expectedEpisodes,
    manualTotalEpisodes: row.manualTotalEpisodes,
    mode: row.mode,
    accountId: null,
    accountIds: row.accountIds?.length ? row.accountIds : (row.accountId ? ['pan:' + row.accountId] : []),
    crossDrive: !!row.crossDrive,
    magnetOffline: !!row.magnetOffline,
    checkIntervalHours: row.checkIntervalHours ?? 6,
    customAirClock: row.customAirClock ?? null,
    airWeekdays: row.airWeekdays || [],
    mainDrives: row.mainDrives || [],
    driveTypes: row.filter?.driveTypes || [],
    qualities: row.filter?.qualities || [],
    includeKeywords: row.filter?.includeKeywords || [],
    excludeKeywords: row.filter?.excludeKeywords || [],
    minEpisodeSizeMb: row.filter?.minEpisodeSizeMb ?? 0,
    maxEpisodeSizeMb: row.filter?.maxEpisodeSizeMb ?? 0,
    weights: { ...(row.filter?.weights || {}) },
  }
  // 编辑沿用订阅已绑定的源:重置成 TMDB 会让后续搜索走错源、易被误绑;仅新建(handleAdd)才强制 TMDB
  metaProvider.value = row.metaProvider || 'douban'
  metaKeyword.value = ''
  metaResults.value = []
  metaLink.value = ''
  formVisible.value = true
}

const resolveLink = () => {
  if (!metaLink.value) return
  resolvingLink.value = true
  axios.get('/api/media-subscriptions/meta/resolve', {params: {url: metaLink.value.trim()}})
      .then(response => {
        const data = response.data
        form.value.metaProvider = data.provider
        form.value.metaId = data.id
        if (data.provider === 'douban' && data.doubanId) {
          form.value.doubanId = data.doubanId
        }
        if (data.season) {
          form.value.season = data.season
        }
        if (!form.value.name && data.name) {
          form.value.name = data.name
        }
        if (data.totalEpisodes && !form.value.expectedEpisodes) {
          form.value.expectedEpisodes = data.totalEpisodes
        }
        ElMessage.success(`已识别 ${providerName(data.provider)} 条目${data.name ? ':' + data.name : ''}`)
        metaLink.value = ''
      }).finally(() => {
    resolvingLink.value = false
  })
}

const searchMeta = () => {
  if (!metaKeyword.value) return
  metaSearching.value = true
  axios.get('/api/media-subscriptions/meta/search', {params: {keyword: metaKeyword.value, provider: metaProvider.value}})
      .then(response => {
        metaResults.value = response.data.items || []
        const errors = response.data.errors || {}
        const failed = Object.entries(errors).map(([provider, message]) => `${providerName(provider)}:${message}`)
        if (failed.length) ElMessage.warning(`部分源失败 - ${failed.join(';')}`)
        if (!metaResults.value.length && !failed.length) ElMessage.info('该源未找到,可换源或留空')
      }).finally(() => {
    metaSearching.value = false
  })
}

const selectMeta = (item: any) => {
  if (form.value.metaProvider === item.provider && form.value.metaId === item.id) {
    form.value.metaProvider = null
    form.value.metaId = null
    // 片单预填/链接解析的豆瓣绑定同时落在 doubanId 上:取消选中时一并解除
    if (item.provider === 'douban' && form.value.doubanId === Number(item.id)) {
      form.value.doubanId = null
    }
  } else {
    form.value.metaProvider = item.provider
    form.value.metaId = item.id
  }
  if (form.value.metaId && !form.value.name) {
    form.value.name = item.name
  }
}

const providerName = (provider: string) => {
  return {douban: '豆瓣', tmdb: 'TMDB', bangumi: 'Bangumi', official: '官方平台'}[provider] || provider || ''
}

/** 标签(中文源名)→ 多源评分表里的分值;无评分返回空(标签仍显示源名占位) */
const ratingOfSource = (label: string) => {
  const source = {'豆瓣': 'douban', TMDB: 'tmdb', Bangumi: 'bangumi'}[label] || label
  return detailData.value?.media.ratings?.[source] || ''
}

const buildBody = () => ({
  name: form.value.name,
  keyword: form.value.keyword,
  customKeywords: form.value.customKeywords.map((k: string) => k.trim()).filter(Boolean).join('\n'),
  season: form.value.season,
  seasonStartEpisode: form.value.seasonStartEpisode ?? 0,
  doubanId: form.value.doubanId,
  metaProvider: form.value.metaProvider,
  metaId: form.value.metaId,
  expectedEpisodes: form.value.expectedEpisodes,
  manualTotalEpisodes: form.value.manualTotalEpisodes ?? 0,
  mode: form.value.mode,
  accountId: form.value.accountId,
  accountIds: form.value.accountIds,
  crossDrive: form.value.crossDrive,
  magnetOffline: form.value.magnetOffline,
  checkIntervalHours: form.value.checkIntervalHours,
  customAirClock: form.value.customAirClock || '',
  airWeekdays: [...new Set(form.value.airWeekdays || [])],
  mainDrives: [...new Set(form.value.mainDrives || [])].slice(0, 2),
  filter: {
    driveTypes: form.value.driveTypes,
    qualities: form.value.qualities,
    includeKeywords: form.value.includeKeywords,
    excludeKeywords: form.value.excludeKeywords,
    minEpisodeSizeMb: form.value.minEpisodeSizeMb,
    maxEpisodeSizeMb: form.value.maxEpisodeSizeMb,
    weights: Object.fromEntries(Object.entries(form.value.weights || {})
      .filter(([, v]) => v !== null && v !== undefined && v !== '')),
  },
})

const save = () => {
  if (!form.value.name) {
    ElMessage.warning('请填写剧名')
    return
  }
  if (form.value.mode === 'TRANSFER' && !(form.value.accountIds || []).length && !form.value.accountId) {
    ElMessage.warning('转存模式需选择至少一个网盘账号')
    return
  }
  // 换季是不可逆的整体重置,先告知后果再保存(取消则不动)
  if (form.value.id && originalSeason.value && form.value.season !== originalSeason.value) {
    ElMessageBox.confirm(
        `季号将从第 ${originalSeason.value} 季改为第 ${form.value.season} 季:旧季的挂载、候选资源与观看进度会被清空,并立即按新季重新搜索挂载。确定切换?`,
        '切换季号', {type: 'warning'})
        .then(doSave)
        .catch(() => {})
    return
  }
  doSave()
}

const doSave = () => {
  saving.value = true
  const body = buildBody()
  const request = form.value.id
      ? axios.post(`/api/media-subscriptions/${form.value.id}`, body)
      : axios.post('/api/media-subscriptions', body)
  request.then(() => {
    ElMessage.success(form.value.id ? '已保存' : '已创建,开始首次搜索(稍后刷新查看结果)')
    if (!form.value.id && navPending.value) {
      navSubscribed.value.add(navPending.value.vod_name)
    }
    formVisible.value = false
    schedule(loadAll, 3000)
  }).finally(() => {
    saving.value = false
  })
}

// 对话框关闭即解除片单条目关联:取消/未保存不误标"已追更",之后再手动新建也不受牵连
watch(formVisible, visible => {
  if (!visible) navPending.value = null
})

const preview = () => {
  const keyword = form.value.keyword || form.value.name
  if (!keyword) {
    ElMessage.warning('请先填写剧名或搜索词')
    return
  }
  previewing.value = true
  previewVisible.value = true
  previewItems.value = []
  axios.post('/api/media-subscriptions/preview', buildBody()).then(response => {
    previewItems.value = response.data
    if (!response.data.length) ElMessage.info('无匹配候选,试试更换关键词')
  }).finally(() => {
    previewing.value = false
  })
}

const handleDelete = (row: SubscriptionDto) => {
  ElMessageBox.confirm(`确定删除订阅「${row.name}」?挂载与候选资源将一并清理${row.mode === 'TRANSFER' ? '(已转存文件保留)' : ''}。`, '删除订阅', {type: 'warning'})
      .then(() => axios.delete(`/api/media-subscriptions/${row.id}`))
      .then(() => {
        ElMessage.success('已删除')
        loadAll()
      }).catch(() => {
  })
}

const checkNow = (row: SubscriptionDto) => {
  axios.post(`/api/media-subscriptions/${row.id}/check`).then(() => {
    ElMessage.success('已开始巡检,稍后刷新查看结果')
    schedule(loadAll, 6000)
  })
}

const transferNow = (row: SubscriptionDto) => {
  axios.post(`/api/media-subscriptions/${row.id}/transfer`).then(() => {
    ElMessage.success('已开始增量转存,结果见动态')
    schedule(loadAll, 15000)
  })
}

const togglePause = (row: SubscriptionDto) => {
  const action = row.status === 'PAUSED' ? 'resume' : 'pause'
  axios.post(`/api/media-subscriptions/${row.id}/${action}`).then(loadAll)
}

const subscribeNextSeason = (row: SubscriptionDto) => {
  axios.get(`/api/media-subscriptions/${row.id}/next-season`).then(response => {
    if (!response.data.available) {
      ElMessage.info(response.data.reason || '暂未发现下一季')
      return
    }
    const season = response.data.season
    ElMessageBox.confirm(`发现第 ${season} 季,立即订阅?`, '多季联动', {type: 'info'})
        .then(() => axios.post('/api/media-subscriptions', {
          name: row.name,
          keyword: row.name,
          season: season,
          metaProvider: row.metaProvider,
          metaId: row.metaId,
          mode: 'FOLLOW',
          filter: row.filter,
        }))
        .then(() => {
          ElMessage.success(`已订阅第 ${season} 季`)
          loadAll()
        }).catch(() => {
    })
  })
}

/** 盘型 → 分享链接提取码参数名(资源页 SharesView shareTypeMeta 同口径;123/夸克实测为 pwd) */
const drivePasswordParam: Record<string, string> = {
  ali: 'password',
  pikpak: 'pwd',
  thunder: 'pwd',
  '123': 'pwd',
  quark: 'pwd',
  '139': 'password',
  uc: 'password',
  '115': 'password',
  '189': 'password',
  baidu: 'pwd',
}

/** 候选资源点击链接:提取码折进 URL 查询参数,打开网盘分享页免手输提取码;
 *  链接已带 pwd=/password=/passcode=(站点源入池时已折)不重复折,无提取码原样返回。 */
const resourceShareLink = (row: ResourceDto) => {
  const link = row.link || ''
  const password = (row.password || '').trim()
  if (!password || !/^https?:\/\//i.test(link)) return link
  const lowered = link.toLowerCase()
  if (lowered.includes('pwd=') || lowered.includes('password=') || lowered.includes('passcode=')) return link
  const param = drivePasswordParam[row.driveName || '']
  if (!param) return link
  return link + (link.includes('?') ? '&' : '?') + param + '=' + password
}

const showResources = (row: SubscriptionDto) => {
  current.value = row
  resourcesVisible.value = true
  loadResources()
}

const loadResources = () => {
  if (!current.value) return
  resourcesLoading.value = true
  const my = ++resourcesSeq
  axios.get(`/api/media-subscriptions/${current.value.id}/resources`).then(response => {
    if (my !== resourcesSeq) return
    resources.value = response.data
  }).finally(() => {
    if (my === resourcesSeq) resourcesLoading.value = false
  })
}

/** 手动添加候选资源:只入池不挂载不动主源(用户反馈"一启用就变主资源"的解法 ——
 *  添加与启用两个动作分开;同链幂等,曾移除/判死的复活回候选池)。 */
const openAddResource = () => {
  addResourceForm.value = {link: '', password: ''}
  addResourceVisible.value = true
}

const openManualMagnet = () => {
  magnetForm.value = {url: '', episode: null}
  magnetSearchKeyword.value = ''
  magnetResults.value = []
  magnetResolved.value = {}
  magnetResolving.value = null
  magnetSubmittingLink.value = null
  magnetVisible.value = true
}

/** 手动提交三态的统一提示/刷新(手动贴链接与搜索结果「入库」共用) */
const handleMagnetResponse = (response: any) => {
  const status = response.data?.status
  if (status === 'completed') {
    const episodes = response.data?.episodes as number[] | undefined
    if (episodes?.length) {
      ElMessage.success(response.data?.message || '已离线下载并入账')
      magnetVisible.value = false
      if (episodesVisible.value) showEpisodes(current.value!)
      if (resourcesVisible.value) loadResources()
      schedule(loadAll, 2000)
    } else {
      ElMessage.warning(response.data?.message || '产物已下载,但未识别出属于本剧的集文件,未入账')
    }
  } else if (status === 'submitted') {
    ElMessage.success(response.data?.message || '已提交,网盘下载中')
    magnetVisible.value = false
  } else {
    ElMessage.error(response.data?.message || '离线下载提交失败')
  }
}

/** 手动磁力补缺:贴磁力/ed2k 提交离线下载账号,下载完成入账补缺集;
 *  超时转后台等待(巡检 PENDING 感知收割),失败保留对话框便于换链接重试。 */
const submitManualMagnet = () => {
  if (!current.value) return
  const url = magnetForm.value.url.trim()
  if (!url) {
    ElMessage.warning('请粘贴磁力链接')
    return
  }
  magnetSaving.value = true
  axios.post(`/api/media-subscriptions/${current.value.id}/magnet`, {
    url,
    episode: magnetForm.value.episode ?? null,
  }).then(handleMagnetResponse).catch((error: any) => {
    const data = error.response?.data
    ElMessage.error(typeof data === 'string' ? data : data?.message || error.message || '提交失败')
  }).finally(() => {
    magnetSaving.value = false
  })
}

/** 磁力候选搜索:关键词空=订阅关键词;填了集号拼进搜索词 */
const searchManualMagnets = () => {
  if (!current.value) return
  magnetSearching.value = true
  axios.get(`/api/media-subscriptions/${current.value.id}/magnet/search`, {
    params: {
      keyword: magnetSearchKeyword.value.trim() || null,
      episode: magnetForm.value.episode ?? null,
    },
  }).then(response => {
    magnetResults.value = response.data || []
    magnetResolved.value = {}
    if (!magnetResults.value.length) ElMessage.info('未搜到磁力资源')
  }).catch((error: any) => {
    const data = error.response?.data
    ElMessage.error(typeof data === 'string' ? data : data?.message || error.message || '搜索失败')
  }).finally(() => {
    magnetSearching.value = false
  })
}

/** 解析磁力种子:拉文件列表并按本剧季口径标集号,提交前确认包内容 */
const resolveMagnet = (item: any) => {
  if (!current.value) return
  magnetResolving.value = item.link
  axios.post(`/api/media-subscriptions/${current.value.id}/magnet/resolve`, {url: item.link}).then(response => {
    magnetResolved.value[item.link] = response.data
  }).catch((error: any) => {
    const data = error.response?.data
    magnetResolved.value[item.link] = {resolved: false,
      message: typeof data === 'string' ? data : data?.message || error.message || '解析失败'}
  }).finally(() => {
    magnetResolving.value = null
  })
}

/** 搜索结果「入库」:直接提交该磁力离线(复用手动提交的三态处理) */
const submitMagnetItem = (item: any) => {
  if (!current.value) return
  magnetSubmittingLink.value = item.link
  axios.post(`/api/media-subscriptions/${current.value.id}/magnet`, {
    url: item.link,
    episode: magnetForm.value.episode ?? null,
  }).then(handleMagnetResponse).catch((error: any) => {
    const data = error.response?.data
    ElMessage.error(typeof data === 'string' ? data : data?.message || error.message || '提交失败')
  }).finally(() => {
    magnetSubmittingLink.value = null
  })
}

const submitAddResource = () => {
  if (!current.value) return
  const link = addResourceForm.value.link.trim()
  if (!link) {
    ElMessage.warning('请粘贴分享链接')
    return
  }
  addResourceSaving.value = true
  axios.post(`/api/media-subscriptions/${current.value.id}/resources`, {
    link,
    password: addResourceForm.value.password.trim() || null,
  }).then(response => {
    if (response.data?.existed) {
      ElMessage.success('该链接已在资源池中,提取码已按填写更新')
    } else {
      ElMessage.success(response.data?.revived ? '已复活为候选(下轮巡检重探)' : '已加入候选池,巡检/补缺时自动探测')
    }
    addResourceVisible.value = false
    loadResources()
  }).finally(() => {
    addResourceSaving.value = false
  })
}

/** 启用候选(挂为补缺源,不动主源):探测落集源行 → 挂到 .sources/ → 自动触发一轮巡检。
 *  与「转主源」分开 —— 回应"点启用就变成主源"。 */
const enableResource = (resource: ResourceDto) => {
  if (!current.value) return
  axios.post(`/api/media-subscriptions/${current.value.id}/resources/${resource.id}/mount`).then(() => {
    ElMessage.success('已开始挂载为补缺源(主源不动),稍后刷新')
    schedule(loadResources, 6000)
    schedule(loadAll, 8000)
  })
}

/** 转主源(已挂载的补缺源升级/手动换源):删旧挂载换到订阅固定路径。 */
const activateResource = (resource: ResourceDto) => {
  if (!current.value) return
  axios.post(`/api/media-subscriptions/${current.value.id}/resources/${resource.id}/activate`).then(() => {
    ElMessage.success('已开始换源,稍后刷新')
    schedule(loadResources, 6000)
    schedule(loadAll, 8000)
  })
}

const pinResource = (resource: ResourceDto) => {
  if (!current.value) return
  axios.post(`/api/media-subscriptions/${current.value.id}/resources/${resource.id}/pin`).then(() => {
    ElMessage.success('已钉选为主源,自动换源不再覆盖')
    schedule(loadResources, 6000)
    schedule(loadAll, 8000)
  })
}

const unpinResource = (resource: ResourceDto) => {
  if (!current.value) return
  axios.post(`/api/media-subscriptions/${current.value.id}/resources/${resource.id}/unpin`).then(() => {
    ElMessage.success('已取消钉选,恢复自动换源')
    schedule(loadResources, 2000)
  })
}

/** 手动移除资源:误挂的异剧源(同名短剧冒领集位)/不想要的源 —— 卸载挂载、清集源行、
 *  墓碑防下轮搜索重新入池;主源不可移除(先换源)。误移除可用「恢复」回候选池。 */
const removeResource = (resource: ResourceDto) => {
  if (!current.value) return
  ElMessageBox.confirm(
      `确定移除「${resource.title || resource.link}」?将卸载其挂载并清除集数记录,之后不再自动入池(可恢复)。`,
      '移除资源', {type: 'warning'}).then(() => {
    axios.delete(`/api/media-subscriptions/${current.value!.id}/resources/${resource.id}`).then(() => {
      ElMessage.success('已移除')
      loadResources()
      if (episodesVisible.value) showEpisodes(current.value!)
      schedule(loadAll, 2000)
    })
  }).catch(() => {})
}

const restoreResource = (resource: ResourceDto) => {
  if (!current.value) return
  axios.post(`/api/media-subscriptions/${current.value.id}/resources/${resource.id}/restore`).then(() => {
    ElMessage.success('已恢复为候选')
    loadResources()
  })
}

/** 资源级起始集号:该资源第 1 集对应全剧第 N 集(季包资源混进连续编号订阅时手动对齐) */
const setResourceStart = (resource: ResourceDto) => {
  if (!current.value) return
  ElMessageBox.prompt(
      '该资源第 1 集对应全剧第几集?(如完结季包实为全剧 153 起填 153;0 = 清除)。修改后该资源的集数记录会重扫',
      '起始集号 - ' + (resource.title || ''), {
        inputValue: resource.startEpisode ? String(resource.startEpisode) : '',
        inputPattern: /^\d{0,4}$/,
        inputErrorMessage: '请输入 0-9999 的数字',
      }).then(({value}) => {
    const startEpisode = parseInt(value, 10)
    axios.post(`/api/media-subscriptions/${current.value!.id}/resources/${resource.id}/episode-start`,
        {startEpisode: isNaN(startEpisode) ? 0 : startEpisode}).then(() => {
      ElMessage.success('起始集号已更新,该资源集数记录将重扫')
      schedule(loadResources, 2000)
      schedule(loadAll, 8000)
    })
  }).catch(() => {})
}

const showEpisodes = (row: SubscriptionDto) => {
  current.value = row
  episodesVisible.value = true
  episodesLoading.value = true
  episodeFilter.value = 'all'
  const my = ++episodesSeq
  axios.get(`/api/media-subscriptions/${row.id}/episodes`).then(response => {
    if (my !== episodesSeq) return
    episodeItems.value = response.data
  }).finally(() => {
    if (my === episodesSeq) episodesLoading.value = false
  })
}

/** 媒体详情:零网络接口,元数据未落库时显示占位(后台预热,稍后再开即有) */
const showDetail = (row: SubscriptionDto) => {
  current.value = row
  detailVisible.value = true
  detailLoading.value = true
  detailData.value = null
  const my = ++detailSeq
  axios.get(`/api/media-subscriptions/${row.id}/detail`).then(response => {
    if (my !== detailSeq) return
    detailData.value = response.data
  }).finally(() => {
    if (my === detailSeq) detailLoading.value = false
  })
}

const reloadDetail = () => {
  if (detailVisible.value && current.value) {
    showDetail(current.value)
  }
}

/** 列表剧名原是 router-link 直跳挂载目录;点击标题让位给详情后,目录浏览入口收进详情抽屉 */
const browseMount = () => {
  const mountPath = detailData.value?.subscription?.mountPath
  if (!mountPath) return
  detailVisible.value = false
  router.push('/vod' + mountPath)
}

/** 刷新元数据:异步任务(TMDB 4 请求/豆瓣桥接),数秒后自动重开详情看新数据 */
const refreshMeta = () => {
  if (!current.value) return
  axios.post(`/api/media-subscriptions/${current.value.id}/refresh-meta`).then(() => {
    ElMessage.success('已开始刷新元数据,稍后自动更新详情')
    schedule(() => {
      reloadDetail()
      loadAll()
    }, 6000)
  })
}

/** 检查更新(轻量,atv-player 语义):刷新元数据对比官方已播 vs 本地,结论进"动态";不搜资源不挂载 */
const checkFromDetail = () => {
  if (!current.value) return
  axios.post(`/api/media-subscriptions/${current.value.id}/check-update`).then(() => {
    ElMessage.success('已开始检查更新,结论见本页数据与「动态」')
    schedule(() => {
      reloadDetail()
      loadAll()
    }, 6000)
  })
}

const showEvents = (row: SubscriptionDto) => {
  current.value = row
  eventsVisible.value = true
  eventsLoading.value = true
  const my = ++eventsSeq
  axios.get(`/api/media-subscriptions/${row.id}/events`).then(response => {
    if (my !== eventsSeq) return
    events.value = response.data
  }).finally(() => {
    if (my === eventsSeq) eventsLoading.value = false
  })
}

const selectAll = () => {
  subscriptions.value.forEach(row => tableRef.value?.toggleRowSelection(row, true))
}
const selectNone = () => {
  tableRef.value?.clearSelection()
}
const invertSelection = () => {
  subscriptions.value.forEach(row => tableRef.value?.toggleRowSelection(row, !selected.value.includes(row)))
}

const batch = (action: string) => {
  const ids = selected.value.map(row => row.id)
  if (action === 'delete') {
    ElMessageBox.confirm(`批量删除 ${ids.length} 个订阅?`, '批量删除', {type: 'warning'})
        .then(() => doBatch(action, ids))
        .catch(() => {
        })
  } else {
    doBatch(action, ids)
  }
}

const doBatch = (action: string, ids: number[]) => {
  axios.post('/api/media-subscriptions/batch', {action, ids}).then(response => {
    ElMessage.success(`已对 ${response.data.affected} 个订阅执行操作`)
    schedule(loadAll, action === 'check' ? 6000 : 500)
  })
}

const exportSubs = () => {
  axios.get('/api/media-subscriptions/export').then(response => {
    const blob = new Blob([JSON.stringify(response.data, null, 2)], {type: 'application/json'})
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'media-subscriptions.json'
    link.click()
    URL.revokeObjectURL(url)
  })
}

const importSubs = () => {
  try {
    const list = JSON.parse(importText.value)
    if (!Array.isArray(list)) {
      ElMessage.error('导入内容必须是订阅数组(导出文件的 JSON 结构)')
      return
    }
    importing.value = true
    axios.post('/api/media-subscriptions/import', list).then(response => {
      ElMessage.success(`导入完成:新建 ${response.data.created},跳过重复 ${response.data.skipped}`)
      importVisible.value = false
      importText.value = ''
      loadAll()
    }).finally(() => {
      importing.value = false
    })
  } catch (e) {
    ElMessage.error('JSON 解析失败')
  }
}

/** 全局资源筛选(msub_pool_filter 单行 JSON);坏配置/未配置回落空(全部门禁关闭) */
const parsePoolFilter = (raw: string) => {
  let parsed: any = {}
  try {
    parsed = raw ? JSON.parse(raw) : {}
  } catch {
    parsed = {}
  }
  return {
    minQuality: typeof parsed.minQuality === 'string' ? parsed.minQuality : '',
    includeKeywords: Array.isArray(parsed.includeKeywords) ? parsed.includeKeywords : [],
    excludeKeywords: Array.isArray(parsed.excludeKeywords) ? parsed.excludeKeywords : [],
    minEpisodeSizeMb: typeof parsed.minEpisodeSizeMb === 'number' ? parsed.minEpisodeSizeMb : 0,
    maxEpisodeSizeMb: typeof parsed.maxEpisodeSizeMb === 'number' ? parsed.maxEpisodeSizeMb : 0,
  }
}

/** 普通用户加载到的资源筛选快照(同款序列化):保存时未改动则跳过,避免把默认空配置存成用户级覆盖(盖掉全局门禁) */
const userPoolRaw = ref('')
const buildPoolFilterValue = () => JSON.stringify({
  minQuality: notifyForm.value.poolMinQuality || '',
  includeKeywords: notifyForm.value.poolIncludeKeywords.map((k: string) => k.trim()).filter(Boolean),
  excludeKeywords: notifyForm.value.poolExcludeKeywords.map((k: string) => k.trim()).filter(Boolean),
  minEpisodeSizeMb: notifyForm.value.poolMinEpisodeSizeMb || 0,
  maxEpisodeSizeMb: notifyForm.value.poolMaxEpisodeSizeMb || 0,
})
const openNotify = () => {
  loadOfflineAccountLabel()
  notifyTab.value = 'general'
  if (!store.admin) {
    // 普通用户:个人 TG 渠道 + 资源筛选偏好走用户级设置(读取回退全局值),其余全局项不展示
    Promise.all([
      axios.get('/api/user-settings/msub_telegram_bot_token'),
      axios.get('/api/user-settings/msub_telegram_chat_id'),
      axios.get('/api/user-settings/msub_notify_quiet_hours'),
      axios.get('/api/user-settings/msub_pool_filter'),
    ]).then(([token, chat, quiet, pool]) => {
      notifyForm.value.botToken = token.data?.value || ''
      notifyForm.value.chatId = chat.data?.value || ''
      notifyForm.value.quietHours = quiet.data?.value || ''
      const poolFilter = parsePoolFilter(pool.data?.value || '')
      notifyForm.value.poolMinQuality = poolFilter.minQuality
      notifyForm.value.poolIncludeKeywords = poolFilter.includeKeywords
      notifyForm.value.poolExcludeKeywords = poolFilter.excludeKeywords
      notifyForm.value.poolMinEpisodeSizeMb = poolFilter.minEpisodeSizeMb
      notifyForm.value.poolMaxEpisodeSizeMb = poolFilter.maxEpisodeSizeMb
      userPoolRaw.value = buildPoolFilterValue()
      notifyLoaded.value = true
      notifyVisible.value = true
    }).catch(() => {
      ElMessage.error('设置加载失败,未打开对话框,请重试')
    })
    return
  }
  axios.get('/api/settings').then(response => {
    const settings = response.data || {}
    notifyForm.value.botToken = settings['msub_telegram_bot_token'] || ''
    notifyForm.value.chatId = settings['msub_telegram_chat_id'] || ''
    notifyForm.value.quietHours = settings['msub_notify_quiet_hours'] || ''
    notifyForm.value.botEnabled = settings['msub_telegram_bot_enabled'] !== 'false'
    notifyForm.value.collectionFallback = settings['msub_collection_fallback'] === 'true'
    notifyForm.value.doubanCookie = settings['douban_cookie'] || ''
    notifyForm.value.tmdbApiKey = settings['tmdb_api_key'] || ''
    notifyForm.value.tmdbApiHost = settings['tmdb_api_host'] || ''
    notifyForm.value.archiveDays = parseInt(settings['msub_archive_days'] || '0') || 0
    notifyForm.value.magnetEpisodeQuota = parseInt(settings['msub_magnet_episode_quota'] || '2') || 2
    notifyForm.value.magnetSubscriptionQuota = parseInt(settings['msub_magnet_subscription_quota'] || '30') || 30
    notifyForm.value.magnetTotalQuota = parseInt(settings['msub_magnet_total_quota'] || '200') || 200
    notifyForm.value.vipAccounts = (settings['msub_vip_accounts'] || '')
        .split(',').map((v: string) => parseInt(v.trim())).filter((v: number) => v > 0)
    notifyForm.value.mainDrives = (settings['msub_main_drives'] || '')
        .split(',').map((v: string) => parseInt(v.trim())).filter((v: number) => v > 0).slice(0, 2)
    notifyForm.value.extendedDrives = (settings['msub_extended_drives'] || '')
        .split(',').map((v: string) => parseInt(v.trim())).filter((v: number) => v > 0)
    const poolFilter = parsePoolFilter(settings['msub_pool_filter'])
    notifyForm.value.poolMinQuality = poolFilter.minQuality
    notifyForm.value.poolIncludeKeywords = poolFilter.includeKeywords
    notifyForm.value.poolExcludeKeywords = poolFilter.excludeKeywords
    notifyForm.value.poolMinEpisodeSizeMb = poolFilter.minEpisodeSizeMb
    notifyForm.value.poolMaxEpisodeSizeMb = poolFilter.maxEpisodeSizeMb
    notifyForm.value.panlianHost = settings['panlian_host'] || ''
    notifyForm.value.panlianUsername = settings['panlian_username'] || ''
    notifyForm.value.panlianPassword = settings['panlian_password'] || ''
    notifyForm.value.panlianCookie = settings['panlian_cookie'] || ''
    notifyForm.value.guanyingHost = settings['guanying_host'] || ''
    notifyForm.value.guanyingUsername = settings['guanying_username'] || ''
    notifyForm.value.guanyingPassword = settings['guanying_password'] || ''
    notifyForm.value.guanyingCookie = settings['guanying_cookie'] || ''
    notifyForm.value.woniuHost = settings['woniu_host'] || ''
    notifyForm.value.woniuUsername = settings['woniu_username'] || ''
    notifyForm.value.woniuPassword = settings['woniu_password'] || ''
    notifyForm.value.woniuCookie = settings['woniu_cookie'] || ''
    notifyForm.value.zencangHost = settings['zencang_host'] || ''
    notifyForm.value.zencangCookie = settings['zencang_cookie'] || ''
    notifyForm.value.pan123communityHost = settings['pan123community_host'] || ''
    notifyForm.value.pan123communityCookie = settings['pan123community_cookie'] || ''
    notifyForm.value.kuafuHost = settings['kuafu_host'] || ''
    notifyForm.value.kuafuCookie = settings['kuafu_cookie'] || ''
    notifyForm.value.panSouUrl = settings['pan_sou_url'] || ''
    notifyForm.value.panSouUsername = settings['pan_sou_username'] || ''
    notifyForm.value.panSouPassword = settings['pan_sou_password'] || ''
    notifyForm.value.panSouSource = settings['pan_sou_source'] || 'all'
    notifyForm.value.panSouChannels = settings['pan_sou_channels'] || 'custom'
    notifyForm.value.panSouConc = settings['pan_sou_conc'] ? +settings['pan_sou_conc'] : null
    notifyForm.value.panSouRefresh = settings['pan_sou_refresh'] === 'true'
    notifyForm.value.panSouFilterInclude = settings['pan_sou_filter_include'] || ''
    notifyForm.value.panSouFilterExclude = settings['pan_sou_filter_exclude'] || ''
    notifyForm.value.tgSearch = settings['tg_search'] || ''
    notifyForm.value.tgSearchApiKey = settings['tg_search_api_key'] || ''
    notifyForm.value.panCheckUrl = settings['pan_check_url'] || ''
    notifyForm.value.panCheckTimeoutMs = settings['pan_check_timeout_ms'] ? +settings['pan_check_timeout_ms'] : null
    notifyForm.value.panSouLinkCheckEnabled = settings['pan_sou_link_check_enabled'] === 'true'
    notifyForm.value.panSouLinkCheckTypes = (settings['pan_sou_link_check_types'] || '')
        .split(',').map((v: string) => v.trim()).filter(Boolean)
    notifyForm.value.panSouLinkCheckMaxCount = parseInt(settings['pan_sou_link_check_max_count'] || '300') || 300
    if (notifyForm.value.panSouUrl) {
      loadPanSouAuth()
    }
    notifyLoaded.value = true
    notifyVisible.value = true
  }).catch(() => {
      // 加载失败绝不能打开空表单:保存会把 30 余项配置(含 botToken/豆瓣 cookie/搜索源凭证/盘搜/TG-Search/资源筛选)整体覆写为空
    ElMessage.error('设置加载失败,未打开对话框,请重试')
  })
}

const saveNotify = () => {
  if (!notifyLoaded.value) {
    ElMessage.warning('设置项尚未加载成功,暂不能保存(防止覆盖为空)')
    return
  }
  const poolFilterValue = buildPoolFilterValue()
  const saves = store.admin ? [
    axios.post('/api/settings', {name: 'msub_telegram_bot_token', value: notifyForm.value.botToken}),
    axios.post('/api/settings', {name: 'msub_telegram_chat_id', value: notifyForm.value.chatId}),
    axios.post('/api/settings', {name: 'msub_notify_quiet_hours', value: notifyForm.value.quietHours.trim()}),
    axios.post('/api/settings', {name: 'msub_telegram_bot_enabled', value: String(notifyForm.value.botEnabled)}),
    axios.post('/api/settings', {name: 'msub_collection_fallback', value: String(notifyForm.value.collectionFallback)}),
    axios.post('/api/settings', {name: 'douban_cookie', value: notifyForm.value.doubanCookie}),
    axios.post('/api/settings', {name: 'tmdb_api_key', value: notifyForm.value.tmdbApiKey}),
    axios.post('/api/settings', {name: 'tmdb_api_host', value: notifyForm.value.tmdbApiHost}),
    // 图床与 API 分线路的预设(NAStool)落 tmdb_image_host,其余(官方/Worker 型,含自定义地址)一律清空走后端跟随 API 池
    ...(notifyForm.value.tmdbApiHost === 'https://tmdb.nastool.org'
        ? [axios.post('/api/settings', {name: 'tmdb_image_host', value: 'https://img.nastool.org'})]
        : [axios.post('/api/settings', {name: 'tmdb_image_host', value: ''})]),
    axios.post('/api/settings', {name: 'msub_archive_days', value: String(notifyForm.value.archiveDays)}),
    axios.post('/api/settings', {name: 'msub_magnet_episode_quota', value: String(notifyForm.value.magnetEpisodeQuota)}),
    axios.post('/api/settings', {name: 'msub_magnet_subscription_quota', value: String(notifyForm.value.magnetSubscriptionQuota)}),
    axios.post('/api/settings', {name: 'msub_magnet_total_quota', value: String(notifyForm.value.magnetTotalQuota)}),
    axios.post('/api/settings', {name: 'msub_vip_accounts', value: notifyForm.value.vipAccounts.join(',')}),
    axios.post('/api/settings', {
      name: 'msub_main_drives',
      value: [...new Set(notifyForm.value.mainDrives)].slice(0, 2).join(','),
    }),
    axios.post('/api/settings', {
      name: 'msub_extended_drives',
      value: [...new Set(notifyForm.value.extendedDrives)].join(','),
    }),
    axios.post('/api/settings', {
      name: 'msub_pool_filter',
      value: poolFilterValue,
    }),
    axios.post('/api/settings', {name: 'panlian_host', value: notifyForm.value.panlianHost.trim()}),
    axios.post('/api/settings', {name: 'panlian_username', value: notifyForm.value.panlianUsername.trim()}),
    axios.post('/api/settings', {name: 'panlian_password', value: notifyForm.value.panlianPassword}),
    axios.post('/api/settings', {name: 'panlian_cookie', value: notifyForm.value.panlianCookie.trim()}),
    axios.post('/api/settings', {name: 'guanying_host', value: notifyForm.value.guanyingHost.trim()}),
    axios.post('/api/settings', {name: 'guanying_username', value: notifyForm.value.guanyingUsername.trim()}),
    axios.post('/api/settings', {name: 'guanying_password', value: notifyForm.value.guanyingPassword}),
    axios.post('/api/settings', {name: 'guanying_cookie', value: notifyForm.value.guanyingCookie.trim()}),
    axios.post('/api/settings', {name: 'woniu_host', value: notifyForm.value.woniuHost.trim()}),
    axios.post('/api/settings', {name: 'woniu_username', value: notifyForm.value.woniuUsername.trim()}),
    axios.post('/api/settings', {name: 'woniu_password', value: notifyForm.value.woniuPassword}),
    axios.post('/api/settings', {name: 'woniu_cookie', value: notifyForm.value.woniuCookie.trim()}),
    axios.post('/api/settings', {name: 'zencang_host', value: notifyForm.value.zencangHost.trim()}),
    axios.post('/api/settings', {name: 'zencang_cookie', value: notifyForm.value.zencangCookie.trim()}),
    axios.post('/api/settings', {name: 'pan123community_host', value: notifyForm.value.pan123communityHost.trim()}),
    axios.post('/api/settings', {name: 'pan123community_cookie', value: notifyForm.value.pan123communityCookie.trim()}),
    axios.post('/api/settings', {name: 'kuafu_host', value: notifyForm.value.kuafuHost.trim()}),
    axios.post('/api/settings', {name: 'kuafu_cookie', value: notifyForm.value.kuafuCookie.trim()}),
    axios.post('/api/settings', {name: 'pan_sou_url', value: notifyForm.value.panSouUrl.trim()}),
    axios.post('/api/settings', {name: 'pan_sou_username', value: notifyForm.value.panSouUsername.trim()}),
    axios.post('/api/settings', {name: 'pan_sou_password', value: notifyForm.value.panSouPassword}),
    axios.post('/api/settings', {name: 'pan_sou_source', value: notifyForm.value.panSouSource}),
    axios.post('/api/settings', {name: 'pan_sou_channels', value: notifyForm.value.panSouChannels}),
    axios.post('/api/settings', {name: 'pan_sou_conc', value: notifyForm.value.panSouConc || ''}),
    axios.post('/api/settings', {name: 'pan_sou_refresh', value: notifyForm.value.panSouRefresh}),
    axios.post('/api/settings', {name: 'pan_sou_filter_include', value: notifyForm.value.panSouFilterInclude.trim()}),
    axios.post('/api/settings', {name: 'pan_sou_filter_exclude', value: notifyForm.value.panSouFilterExclude.trim()}),
    axios.post('/api/settings', {name: 'tg_search', value: notifyForm.value.tgSearch.trim()}),
    axios.post('/api/settings', {name: 'tg_search_api_key', value: notifyForm.value.tgSearchApiKey.trim()}),
    axios.post('/api/settings', {name: 'pan_check_url', value: notifyForm.value.panCheckUrl.trim()}),
    axios.post('/api/settings', {name: 'pan_check_timeout_ms', value: notifyForm.value.panCheckTimeoutMs || ''}),
    axios.post('/api/settings', {name: 'pan_sou_link_check_enabled', value: notifyForm.value.panSouLinkCheckEnabled}),
    axios.post('/api/settings', {name: 'pan_sou_link_check_types', value: notifyForm.value.panSouLinkCheckTypes.join(',')}),
    axios.post('/api/settings', {name: 'pan_sou_link_check_max_count', value: notifyForm.value.panSouLinkCheckMaxCount}),
  ] : (() => {
    // 普通用户:仅写个人 TG 渠道与资源筛选偏好({key}:u{uid} 用户级行);空 botToken/chatId = 删除覆盖、回退全局
    const saves = [
      axios.put('/api/user-settings/msub_telegram_bot_token', {name: 'msub_telegram_bot_token', value: notifyForm.value.botToken}),
      axios.put('/api/user-settings/msub_telegram_chat_id', {name: 'msub_telegram_chat_id', value: notifyForm.value.chatId}),
      axios.put('/api/user-settings/msub_notify_quiet_hours', {name: 'msub_notify_quiet_hours', value: notifyForm.value.quietHours.trim()}),
    ]
    if (poolFilterValue !== userPoolRaw.value) {
      saves.push(axios.put('/api/user-settings/msub_pool_filter', {name: 'msub_pool_filter', value: poolFilterValue}))
    }
    return saves
  })()
  notifySaving.value = true
  // tsconfig lib 无 es2020(无 Promise.allSettled),逐项吞错再计数等价实现
  Promise.all(saves.map(p => p.then(() => true, () => false))).then(results => {
    const failed = results.filter(ok => !ok).length
    if (failed) {
      ElMessage.error(`${saves.length - failed} 项已保存,${failed} 项失败,请检查后重试(对话框保留)`)
    } else {
      ElMessage.success('已保存(下轮巡检生效)')
      notifyVisible.value = false
      loadGlobalPoolFilter() // 全局单集下限变了,新建订阅表单「跟随全局」的展示值同步刷新
    }
  }).finally(() => {
    notifySaving.value = false
  })
}

// 同名不同季的订阅(如末日地堡 S3/S4)在列表剧名后补季号区分,只影响展示;
// 名称本身已带季标记(如"龙之家族 第三季")则不再重复追加
const NAME_SEASON_MARK = /(第\s*[0-9一二三四五六七八九十]{1,3}\s*季|season\s*\d{1,2}|[Ss]\d{1,2})/i
const displayName = (row: any) =>
  row?.season > 1 && !NAME_SEASON_MARK.test(row.name) ? `${row.name} 第${row.season}季` : row?.name

const statusText = (status: string) => {
  switch (status) {
    case 'ACTIVE':
      return '连载中'
    case 'PAUSED':
      return '已暂停'
    case 'ENDED':
      return '已完结'
    case 'ERROR':
      return '异常'
    default:
      return status
  }
}

const statusType = (status: string) => {
  switch (status) {
    case 'ACTIVE':
      return 'success'
    case 'PAUSED':
      return 'info'
    case 'ENDED':
      return ''
    case 'ERROR':
      return 'danger'
    default:
      return 'info'
  }
}

const stateType = (state: string | null) => {
  if (state === 'MOUNTED') return 'success'
  if (state === 'RETIRED') return 'danger'
  if (state === 'REJECTED') return 'danger'
  if (state === 'REMOVED') return 'info'
  return 'info'
}

/** 打分权重维度(与后端 CheckService.WEIGHT_DEFAULTS 一一对应;留空用默认值) */
const weightDefs: { key: string; label: string; value: number }[] = [
  { key: 'recency.recent', label: '30天内发布', value: 30 },
  { key: 'recency.quarter', label: '3个月内', value: 15 },
  { key: 'recency.old', label: '较旧', value: 5 },
  { key: 'quality.uhd', label: '4K', value: 25 },
  { key: 'quality.fhd', label: '1080P', value: 15 },
  { key: 'quality.hd', label: '720P', value: 8 },
  { key: 'quality.prefer', label: '清晰度偏好词', value: 10 },
  { key: 'drive.prefer', label: '盘类型偏好', value: 20 },
  { key: 'drive.outside', label: '偏好外盘', value: -10 },
  { key: 'drive.main', label: '主网盘', value: 15 },
  { key: 'account', label: '已配账号', value: 8 },
  { key: 'account.vip', label: 'VIP账号', value: 15 },
  { key: 'source.wanou', label: '玩偶源', value: 22 },
  { key: 'source.woniu', label: '蜗牛源', value: 20 },
  { key: 'source.panlian', label: '盘链源', value: 12 },
  { key: 'source.panju', label: '盘聚源(SeedHub)', value: 12 },
  { key: 'source.guanying', label: '观影源', value: 12 },
  { key: 'source.xb6v', label: '6V磁力源', value: 12 },
  { key: 'source.zencang', label: '123臻藏源', value: 12 },
  { key: 'source.pan123community', label: '123社区源', value: 12 },
  { key: 'source.kuafu', label: '夸父源', value: 12 },
  { key: 'baidu.free', label: '百度免会员', value: 17 },
  { key: 'pan115', label: '115追更弱', value: -10 },
  { key: 'pack.complete', label: '完结包', value: -6 },
  { key: 'size.fit', label: '体积合理', value: 10 },
  { key: 'keyword.include', label: '命中包含词', value: 10 },
  { key: 'match.title', label: '标题归属', value: 15 },
  { key: 'match.season', label: '季标记匹配', value: 10 },
  { key: 'progress.lead', label: '集数领先', value: 8 },
  { key: 'progress.lag', label: '集数落后', value: -8 },
  { key: 'single.episode', label: '单集链接', value: -40 },
]

/** 集数矩阵:集源行状态(取链事实)→ 标签 */
const matrixStateType = (state: string) => {
  if (state === 'VERIFIED' || state === 'TRANSFER') return 'success'
  if (state === 'FAILED') return 'danger'
  if (state === 'MISSING') return 'info'
  if (state === 'FALLBACK') return 'primary'
  return 'warning' // LISTED:列得出、未验证
}

const matrixStateLabel = (src: { state: string }) => {
  switch (src.state) {
    case 'VERIFIED': return '✓ 已验证'
    case 'FAILED': return '✗ 取链失败'
    case 'MISSING': return '文件已消失'
    case 'TRANSFER': return '已转存'
    case 'FALLBACK': return '采集直链'
    default: return '未验证'
  }
}

/** 单集平均文件大小(字节)转可读文本;null = 该资源还没探测过 */
const formatSize = (bytes: number | null) => {
  if (!bytes) return '-'
  if (bytes >= 1024 * 1024 * 1024) return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return Math.round(bytes / 1024) + ' KB'
}

const stateLabel = (state: string | null) => {
  switch (state) {
    case 'MOUNTED': return '已挂载'
    case 'RETIRED': return '已退役'
    case 'REJECTED': return '已拒绝'
    case 'REMOVED': return '已移除'
    default: return '候选'
  }
}

const eventType = (type: string) => {
  switch (type) {
    case 'NEW_EPISODE':
    case 'RESUMED':
      return 'success'
    case 'GAP_FILLED':
      return 'warning'
    case 'SOURCE_INVALID':
    case 'ERROR':
    case 'TRANSFER_FAILED':
      return 'danger'
    case 'SOURCE_REPLACED':
    case 'DRIVE_LINE':
      return 'primary'
    default:
      return 'info'
  }
}

const eventTypeName = (type: string) => {
  const names: Record<string, string> = {
    NEW_EPISODE: '新集更新',
    SOURCE_INVALID: '主源失效',
    SOURCE_REPLACED: '换源',
    DRIVE_LINE: '分盘线路',
    GAP_FILLED: '补缺',
    POOL_FILLED: '候选池',
    TRANSFER_DONE: '转存完成',
    TRANSFER_FAILED: '转存失败',
    UPGRADE_AVAILABLE: '升级提醒',
    ARCHIVED: '归档',
    ERROR: '异常',
    ENDED: '完结',
    RESUMED: '自动重开',
    UPDATE_CHECK: '更新检查',
  }
  return names[type] || type
}

const eventDetail = (event: EventDto) => {
  return eventTypeName(event.type) + ':' + (event.detail || '')
}

/** 连续集号压成区间:107-131,135-140;区间过多时截断并附总数 */
const compactNumbers = (numbers: number[]) => {
  if (numbers.length === 0) return ''
  const sorted = [...numbers].sort((a, b) => a - b)
  const ranges: string[] = []
  let start = sorted[0]
  let prev = sorted[0]
  for (let i = 1; i <= sorted.length; i++) {
    if (sorted[i] === prev + 1) {
      prev = sorted[i]
      continue
    }
    ranges.push(start === prev ? `${start}` : `${start}-${prev}`)
    start = prev = sorted[i]
  }
  const display = ranges.slice(0, 6)
  if (ranges.length > 6) return display.join(',') + ` 等${numbers.length}集`
  return ranges.join(',')
}

/** 进度分母:官方总集数滞后于资源现实时(长番官方 1212/本地已到 1270)以观测最大集号兜底,避免 1243/1212 倒挂 */
// 分季订阅对齐(seasonStartEpisode)的在播季不显示自动分母:officialTotal/maxEpisode 是全剧连续
// 集号空间且登记滞后(TMDB 200 vs 腾讯 181 都不可信),腾讯分季登记数还会随播出继续长,推本季体量
// 都是假精度 —— 分母只认手填期望集数;完结显示「N集完结」,与 TVBox buildRemarks 同口径
const seasonWindowed = (row: SubscriptionDto): boolean => (row.seasonStartEpisode ?? 0) > 1
const progressTotal = (row: SubscriptionDto): number | null => {
    if (seasonWindowed(row)) {
        return (row.expectedEpisodes ?? 0) || null
    }
    return Math.max(row.officialTotal ?? 0, row.expectedEpisodes ?? 0, row.maxEpisode ?? 0) || null
}
// 官方已播的季内计数(与 currentEpisodes 同空间比较;绝对集号空间里的官方已播数要平移)
const seasonOffset = (row: SubscriptionDto): number =>
    (row.seasonStartEpisode ?? 0) > 1 ? (row.seasonStartEpisode ?? 0) - 1 : 0
const airedInSeason = (row: SubscriptionDto): number => (row.officialEpisodes ?? 0) - seasonOffset(row)

const formatTime = (time: number | null) => {
  if (!time) return '-'
  // 与后端日程分桶同口径(北京时间):非东八区浏览器上避免「今天」格子与钟点互相矛盾
  // 带周几(欧美剧/追番周播,「周六 04:00」一眼对上更新日);秒无信息量,去掉换列宽
  return new Date(time).toLocaleString('zh-CN', {
    hour12: false, timeZone: 'Asia/Shanghai',
    year: 'numeric', month: 'numeric', day: 'numeric',
    weekday: 'short', hour: '2-digit', minute: '2-digit',
  })
}

const formatClock = (time: number) => {
  return new Date(time).toLocaleTimeString('zh-CN', {hour12: false, hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Shanghai'})
}
</script>

<style scoped>
.stats-row {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.stat-card {
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 10px 18px;
  min-width: 90px;
  text-align: center;
}

.stat-card.danger .stat-value {
  color: var(--el-color-danger);
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
}

.stat-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.inbox-card {
  margin-bottom: 12px;
  padding: 10px 16px;
}

.schedule-card {
  margin-bottom: 12px;
  padding: 10px 12px;
  overflow-x: auto;
}

.schedule-strip {
  display: flex;
  gap: 8px;
  min-width: max-content;
}

.schedule-day {
  min-width: 118px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 6px 8px;
}

.schedule-day.today {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.schedule-day-header {
  font-weight: 600;
  margin-bottom: 4px;
}

.schedule-item {
  font-size: 12px;
  line-height: 1.7;
}

.schedule-item.paused {
  opacity: 0.5;
}

.schedule-clock {
  color: var(--el-color-primary);
  margin-right: 4px;
}

.schedule-empty {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.inbox-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
}

.inbox-timeline {
  margin-top: 12px;
  max-height: 300px;
  overflow-y: auto;
}

.batch-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 10px;
}

.name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.cover {
  width: 40px;
  height: 56px;
  border-radius: 4px;
  flex-shrink: 0;
}

.cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-dark);
  color: var(--el-text-color-secondary);
}

.detail-hero {
  margin-bottom: 16px;
}

.detail-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.detail-backdrop {
  aspect-ratio: 16 / 9; /* TMDB 背景图为 16:9,按宽度自适应高度完整展示(固定高度会上下裁图) */
  border-radius: 8px;
  overflow: hidden;
  background: var(--el-fill-color-dark);
  position: relative;
}

/* 多图轮播:候选层叠,淡入淡出切换 */
.detail-backdrop-layer {
  position: absolute;
  inset: 0;
  opacity: 0;
  transition: opacity 0.9s ease;
}

.detail-backdrop-layer.active {
  opacity: 1;
}

.detail-backdrop-img {
  width: 100%;
  height: 100%;
}

.detail-head {
  display: flex;
  gap: 16px;
  position: relative;
}

.detail-poster {
  width: 120px;
  height: 170px;
  border-radius: 6px;
  flex-shrink: 0;
  font-size: 32px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.35);
  margin-top: 8px;
}

.detail-info {
  flex: 1;
  min-width: 0;
  padding-top: 4px;
}

.detail-genres {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin: 4px 0;
}

.detail-ext-link {
  color: var(--el-color-primary);
  text-decoration: none;
  font-size: 12px;
  margin-right: 10px;
}

.detail-ext-link:hover {
  text-decoration: underline;
}

.detail-links-row {
  margin: 4px 0 2px;
}

.detail-ratings-row {
  display: flex;
  gap: 6px;
  align-items: center;
  margin: 2px 0 4px;
}

.detail-cast {
  display: flex;
  gap: 12px;
  overflow-x: auto;
  padding: 12px 0 4px;
}

.cast-card {
  width: 92px;
  flex-shrink: 0;
  text-align: center;
}

.cast-avatar {
  width: 80px;
  height: 110px;
  border-radius: 6px;
  background: var(--el-fill-color-dark);
}

.cast-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  color: var(--el-text-color-secondary);
}

.cast-name {
  font-size: 13px;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cast-role {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-title {
  font-size: 16px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.detail-overview {
  margin-top: 8px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 5;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.episode-detail {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 4px 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  line-height: 1.6;
}

.episode-still {
  width: 160px;
  height: 90px;
  border-radius: 4px;
  flex-shrink: 0;
  background: var(--el-fill-color-dark);
}

.name-link {
  color: var(--el-color-primary);
  text-decoration: none;
  cursor: pointer;
}

.name-link:hover {
  text-decoration: underline;
}

.cover-click {
  cursor: pointer;
}

.sub-text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.sub-text.danger {
  color: var(--el-color-danger);
}

.resource-link {
  color: var(--el-color-primary);
  text-decoration: none;
}

.resource-link:hover {
  text-decoration: underline;
}

.resource-passcode {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.resources-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.meta-search {
  display: flex;
  gap: 8px;
  width: 100%;
}

.meta-results {
  margin-top: 8px;
  max-height: 240px;
  overflow-y: auto;
  width: 100%;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px;
  cursor: pointer;
  border-radius: 4px;
}

.meta-item:hover {
  background: var(--el-fill-color-light);
}

.meta-item.selected {
  background: var(--el-color-primary-light-9);
  outline: 1px solid var(--el-color-primary-light-7);
}

.meta-cover {
  width: 27px;
  height: 38px;
  border-radius: 2px;
  background: var(--el-fill-color-dark);
  flex-shrink: 0;
}

.meta-info {
  font-size: 13px;
}

.nav-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 10px;
  margin-bottom: 12px;
}

.nav-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
  gap: 12px;
  min-height: 200px;
}

.nav-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 4px;
}

.nav-cover {
  width: 100%;
  aspect-ratio: 2 / 3;
  border-radius: 4px;
  background: var(--el-fill-color);
}

.nav-cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-dark);
}

.nav-title {
  font-size: 13px;
  line-height: 1.3;
  height: 34px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.nav-meta {
  display: flex;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  min-height: 18px;
}

.nav-detail {
  display: flex;
  gap: 18px;
  min-height: 260px;
}

.nav-detail-poster {
  width: 180px;
  aspect-ratio: 2 / 3;
  flex-shrink: 0;
  border-radius: 6px;
  background: var(--el-fill-color);
}

.nav-detail-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.nav-detail-title {
  font-size: 18px;
  font-weight: 600;
  line-height: 1.4;
}

.nav-detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.nav-detail-overview {
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.nav-pager {
  display: flex;
  justify-content: center;
  margin-top: 14px;
}

.episode-filter {
  margin-bottom: 10px;
}

.magnet-search-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.magnet-results {
  max-height: 320px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.magnet-result-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 6px 10px;
}

.magnet-result-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.magnet-result-title {
  flex: 1;
  min-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.magnet-files {
  margin-top: 4px;
  padding-left: 8px;
  border-left: 2px solid var(--el-border-color-lighter);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.magnet-file-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.magnet-file-name {
  flex: 1;
  min-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.episode-matrix {
  padding: 4px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.episode-matrix-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.matrix-title {
  min-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.weights-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 4px 16px;
  width: 100%;
}

.weights-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
</style>
