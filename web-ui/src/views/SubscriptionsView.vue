<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">订阅管理</h1>
      <div class="page-actions">
        <el-select v-if="enabledToken && tokens.length > 1" v-model="selectedToken" placeholder="选择安全Token" style="width: 120px; margin-right: 8px">
          <el-option v-for="item in tokens" :key="item" :label="item" :value="item"/>
        </el-select>
        <el-button @click="load">刷新</el-button>
        <el-button @click="showGlobalConfig">全局配置</el-button>
        <el-button @click="showPlugins">订阅源管理</el-button>
        <el-button @click="showPluginFilters">过滤器管理</el-button>
        <el-button @click="showScan">同步影视</el-button>
        <el-button @click="showPush" v-if="devices.length">推送配置</el-button>
        <el-button type="primary" @click="handleAdd">添加</el-button>
      </div>
    </div>

    <div class="page-card">
    <div class="table-scroll-wrapper">
      <el-table :data="subscriptions" v-loading="loading" border style="width: 100%; min-width: 800px">
      <!--      <el-table-column prop="id" label="ID" sortable width="70"/>-->
      <el-table-column prop="sid" label="订阅ID" sortable width="180"/>
      <el-table-column prop="name" label="名称" sortable width="180"/>
      <el-table-column prop="url" label="原始配置URL" sortable>
        <template #default="scope">
          <a :href="scope.row.url" target="_blank">{{ scope.row.url }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="url" label="TvBox配置地址" sortable>
        <template #default="scope">
          <a :href="currentUrl+'/sub'+token+'/'+scope.row.sid" target="_blank">{{ currentUrl }}/sub{{
              token
            }}/{{ scope.row.sid }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="url" label="多仓聚合地址" sortable>
        <template #default="scope">
          <a :href="currentUrl+'/repo'+token+'/'+scope.row.sid" target="_blank">{{ currentUrl }}/repo{{
              token
            }}/{{ scope.row.sid }}</a>
        </template>
      </el-table-column>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="handleEdit(scope.row)" v-if="scope.row.id">
            编辑
          </el-button>
          <el-button link type="primary" size="small" @click="showDetails(scope.row)">
            数据
          </el-button>
          <el-button link type="danger" size="small" @click="handleDelete(scope.row)" v-if="scope.row.id">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    </div>

    <el-row>
      猫影视配置接口：
      <a :href="openUrl" target="_blank">{{ openUrl }}</a>
      <el-button size="small" style="margin-left: 8px" @click="copyUrl(openUrl)">复制</el-button>
    </el-row>
    <el-row>
      猫影视node配置接口：
      <a :href="nodeUrl" target="_blank">{{ nodeUrl2 }}</a>
      <el-button size="small" style="margin-left: 8px" @click="copyUrl(nodeUrl2)">复制</el-button>
    </el-row>
    <el-row>
      PG包本地： {{ pgLocal }}
      &nbsp;&nbsp;
      <a href="https://github.com/power721/pg/releases" target="_blank">PG包远程</a>： {{ pgRemote }}
      <span class="hint"></span>
      <span v-if="pgLocal==pgRemote"><el-icon color="green"><Check/></el-icon></span>
      <span v-else><el-icon color="orange"><Warning/></el-icon></span>
    </el-row>
<!--    <el-row>-->
<!--      真心全量包本地： {{ zxLocal2 }}-->
<!--      真心全量包远程： {{ zxRemote2 }}-->
<!--      <span class="hint"></span>-->
<!--      <span v-if="zxLocal2==zxRemote2"><el-icon color="green"><Check/></el-icon></span>-->
<!--      <span v-else><el-icon color="orange"><Warning/></el-icon></span>-->
<!--    </el-row>-->
    <el-row>
      真心包本地： {{ zxLocal }}
      &nbsp;&nbsp;
      <a href="https://github.com/power721/ZX/releases" target="_blank">真心包远程</a>： {{ zxRemote }}
      <span class="hint"></span>
      <span v-if="zxLocal==zxRemote"><el-icon color="green"><Check/></el-icon></span>
      <span v-else><el-icon color="orange"><Warning/></el-icon></span>
    </el-row>
    <el-row>
      潇洒包本地： {{ xsLocal }}
      &nbsp;&nbsp;
      潇洒包远程： {{ xsRemote }}
      <span class="hint"></span>
      <span v-if="xsLocal==xsRemote"><el-icon color="green"><Check/></el-icon></span>
      <span v-else><el-icon color="orange"><Warning/></el-icon></span>
    </el-row>
    <el-row>
      <el-button @click="syncCat">同步文件</el-button>
    </el-row>
    </div>

    <el-dialog v-model="formVisible" :title="dialogTitle">
      <el-form :model="form">
        <el-form-item label="订阅ID" label-width="140" required>
          <el-input v-model="form.sid" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="名称" label-width="140" required>
          <el-input v-model="form.name" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="配置URL" label-width="140">
          <el-input v-model="form.url" autocomplete="off" placeholder="支持多个，逗号分割。留空使用默认配置。"/>
        </el-form-item>
<!--        <el-form-item label="排序字段" label-width="140">-->
<!--          <el-input v-model="form.sort" autocomplete="off" placeholder="留空保持默认排序"/>-->
<!--        </el-form-item>-->
        <el-form-item label="定制" label-width="140">
          <el-button @click="openEditor(false)">🎨 可视化编辑</el-button>
          <el-button @click="openRawJsonEditor">JSON编辑</el-button>
          <span class="hint">{{ form.override ? '已配置' : '未配置' }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" :title="dialogTitle" :fullscreen="true">
      <div>
        <p>配置URL：</p>
        <a :href="form.url" target="_blank">{{ form.url }}</a>
      </div>
      <h2>JSON数据</h2>
      <el-tabs v-model="detailTab">
        <el-tab-pane label="JSON" name="json">
          <el-scrollbar height="800px">
            <json-viewer :value="jsonData" expanded copyable show-double-quotes :show-array-index="false"
                         :expand-depth=5></json-viewer>
          </el-scrollbar>
        </el-tab-pane>
        <el-tab-pane label="Raw" name="raw">
          <el-input type="textarea" :rows="35" :model-value="rawJsonData" readonly/>
        </el-tab-pane>
      </el-tabs>
      <div class="json"></div>
      <template #footer>
      <span class="dialog-footer">
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除订阅" width="30%">
      <p>是否删除订阅 - {{ form.name }}</p>
      <p>{{ form.url }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteSub">删除</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="tgVisible" title="登陆Telegram" width="60%" @close="cancelLogin">
      <el-form>
        <el-form-item label="登陆方式" label-width="140">
          <el-radio-group v-model="tgAuthType" class="ml-4" @change="setAuthType">
            <el-radio label="qr" size="large">二维码</el-radio>
            <el-radio label="code" size="large">验证码</el-radio>
          </el-radio-group>
        </el-form-item>
        <div v-if="tgAuthType=='qr'&&tgPhase==1&&base64QrCode!=''">
          <img alt="qr" :src="'data:image/png;base64,'+ base64QrCode" style="width: 500px;">
          <p>二维码30秒内有效。</p>
          <el-form-item>
            <el-button type="primary" @click="setScanned">我已经扫码</el-button>
          </el-form-item>
        </div>
        <el-form-item label="电话号码" label-width="140" required v-if="tgAuthType=='code'&&tgPhase==1">
          <el-input v-model="tgPhone" autocomplete="off" placeholder="+8612345678901"/>
          <el-button @click="sendTgPhone">输入</el-button>
        </el-form-item>
        <el-form-item label="验证码" label-width="140" required v-if="tgAuthType=='code'&&tgPhase==3">
          <el-input v-model="tgCode" autocomplete="off"/>
          <el-button @click="sendTgCode">输入</el-button>
        </el-form-item>
        <el-form-item label="密码" label-width="140" required v-if="tgPhase==5">
          <el-input v-model="tgPassword" autocomplete="off"/>
          <el-button @click="sendTgPassword">输入</el-button>
        </el-form-item>
        <div v-if="user.id">
          <div>登陆成功</div>
          <div>用户ID： {{ user.id }}</div>
          <div>用户名： {{ user.username }}</div>
          <div>姓名： {{ user.first_name }} {{ user.last_name }}</div>
        </div>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button type="primary" @click="login">登陆</el-button>
        <el-button type="danger" @click="logout">退出登陆</el-button>
        <!--        <el-button @click="reset">重置</el-button>-->
        <el-button @click="cancelLogin">取消</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="scanVisible" title="影视设备">
      <el-row>
        <el-col span="8">
          <div>影视扫码添加AList TvBox</div>
          <img alt="qr" :src="'data:image/png;base64,'+ base64QrCode" style="width: 200px;">
        </el-col>
        <el-col span="10">
          <el-input v-model="device.ip" style="width: 200px" placeholder="输入影视IP或者URL"
                    @keyup.enter="addDevice"></el-input>
          <el-button @click="addDevice">添加</el-button>
        </el-col>
        <el-col span="6">
          <el-button @click="scanDevices">扫描设备</el-button>
        </el-col>
      </el-row>

      <div class="table-scroll-wrapper">
        <el-table :data="devices" v-loading="loadingDevices" border style="width: 100%; min-width: 800px">
        <el-table-column prop="name" label="名称" sortable width="180"/>
        <el-table-column prop="uuid" label="ID" sortable width="180"/>
        <el-table-column prop="ip" label="URL地址" sortable>
          <template #default="scope">
            <a :href="scope.row.ip" target="_blank">{{ scope.row.ip }}</a>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="140">
          <template #default="scope">
            <el-button link type="primary" size="small" @click="syncHistory(scope.row.id)">同步</el-button>
            <el-button link type="danger" size="small" @click="showDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      </div>
    </el-dialog>

    <el-dialog v-model="confirm" title="删除影视设备" width="30%">
      <p>是否删除影视设备？</p>
      <p> {{ device.name }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="confirm = false">取消</el-button>
        <el-button type="danger" @click="deleteDevice">删除</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="push" title="推送订阅配置" width="30%">
      <el-form label-width="auto">
        <el-form-item label="影视设备" required>
          <el-select
            v-model="pushForm.id"
            style="width: 240px"
          >
            <el-option
              v-for="item in devices"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="安全Token" required>
          <el-select
            v-model="pushForm.token"
            style="width: 240px"
            @change="onTokenChange"
          >
            <el-option
              v-for="item in tokens"
              :key="item"
              :label="item"
              :value="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="订阅" required>
          <el-select
            v-model="pushForm.sid"
            style="width: 240px"
            @change="onTokenChange"
          >
            <el-option
              v-for="item in subscriptions"
              :key="item.sid"
              :label="item.name"
              :value="item.sid"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="订阅地址" required>
          <a :href="pushForm.url" target="_blank">{{pushForm.url}}</a>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="push = false">取消</el-button>
        <el-button type="primary" @click="pushConfig">推送</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="pluginVisible" title="订阅源管理" fullscreen>
      <el-form :inline="true" :model="pluginForm">
        <el-form-item label="插件地址" required>
          <el-input v-model="pluginForm.url" style="width: 460px" placeholder="https://example.com/plugin.txt 或 plugin.py"/>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="pluginForm.name" style="width: 180px" placeholder="留空用默认"/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="addPlugin">添加插件</el-button>
        </el-form-item>
      </el-form>

      <el-form :inline="true" :model="pluginImportForm">
        <el-form-item label="仓库地址" required>
          <el-input
            v-model="pluginImportForm.url"
            style="width: 460px"
            placeholder="https://github.com/xxx/tvbox 或 spiders_v2.json 地址"
            :disabled="importingPlugins"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="importingPlugins" :disabled="!pluginImportForm.url.trim()" @click="importPlugins">
            导入仓库
          </el-button>
          <el-button type="primary" @click="openPluginCompiler">三方插件编译</el-button>
        </el-form-item>
      </el-form>
      <el-progress v-if="importingPlugins" :percentage="100" :indeterminate="true" :duration="5"/>

      <el-form :inline="true" :model="pluginSettingsForm">
        <el-form-item label="插件运行模式">
          <el-select v-model="pluginSettingsForm.pluginRunMode" style="width: 160px">
            <el-option v-for="item in pluginRunModeOptions" :key="item.value" :label="item.label" :value="item.value"/>
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="savePluginSettings">保存设置</el-button>
        </el-form-item>
        <el-form-item>
          <el-button type="danger" :disabled="selectedPluginIds.length === 0" @click="deleteSelectedPlugins">
            批量删除
          </el-button>
        </el-form-item>
      </el-form>

      <!-- GitHub 代理列表管理 -->
      <el-collapse v-model="githubProxyCollapseActive" style="margin-top: 20px">
        <el-collapse-item name="github-proxy">
          <template #title>
            <div style="display: flex; align-items: center; width: 100%;">
              <el-icon style="margin-right: 8px; transition: transform 0.3s;" :style="{ transform: githubProxyCollapseActive.includes('github-proxy') ? 'rotate(90deg)' : 'rotate(0deg)' }">
                <ArrowRight />
              </el-icon>
              <span style="font-weight: 500">GitHub 代理配置（多个，自动 fallback，最多 5 个）</span>
            </div>
          </template>

          <div style="margin-bottom: 10px">
            <el-tooltip content="从预设节点中选择或输入自定义代理地址" placement="top">
              <el-button @click="showAddProxyDialog">添加代理</el-button>
            </el-tooltip>

            <el-tooltip content="测速当前列表中的所有代理节点" placement="top">
              <el-button @click="benchmarkAllProxies" :loading="benchmarking">
                {{ benchmarking ? '测速中...' : '批量测速' }}
              </el-button>
            </el-tooltip>

            <el-tooltip content="测速所有预设节点并自动选择最快的 5 个" placement="top">
              <el-button type="success" @click="autoSelectFastest" :loading="benchmarking">
                智能选择
              </el-button>
            </el-tooltip>

            <el-tooltip content="保存代理列表到 /data/github_proxy.txt" placement="top">
              <el-button type="primary" @click="saveGitHubProxyList">保存</el-button>
            </el-tooltip>
          </div>

          <el-table :data="githubProxyList" border style="width: 100%; margin-bottom: 20px">
            <el-table-column label="优先级" width="80" align="center">
              <template #default="scope">
                {{ scope.$index + 1 }}
              </template>
            </el-table-column>
            <el-table-column label="代理地址" min-width="300">
              <template #default="scope">
                <span v-if="!scope.row">无代理（直连）</span>
                <span v-else>{{ scope.row }}</span>
              </template>
            </el-table-column>
            <el-table-column label="测速结果" width="120">
              <template #default="scope">
                <span v-if="benchmarkResults.get(scope.row)">
                  {{ formatBenchmarkResult(benchmarkResults.get(scope.row)) }}
                </span>
                <span v-else style="color: #909399">-</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="180" align="center">
              <template #default="scope">
                <el-tooltip content="提高优先级" placement="top">
                  <el-button link type="primary" @click="moveProxyUp(scope.$index)" :disabled="scope.$index === 0">
                    上移
                  </el-button>
                </el-tooltip>
                <el-tooltip content="降低优先级" placement="top">
                  <el-button link type="primary" @click="moveProxyDown(scope.$index)" :disabled="scope.$index === githubProxyList.length - 1">
                    下移
                  </el-button>
                </el-tooltip>
                <el-tooltip content="从列表中移除" placement="top">
                  <el-button link type="danger" @click="removeProxy(scope.$index)">
                    删除
                  </el-button>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>

      <!-- 智能选择对话框 -->
      <el-dialog
        v-model="smartSelectDialogVisible"
        title="智能选择 GitHub 代理"
        width="900px"
        :close-on-click-modal="false">
        <div style="margin-bottom: 16px;">
          <el-alert
            type="info"
            :closable="false"
            show-icon>
            <template #title>
              <span v-if="benchmarking">正在测速所有预设节点，请稍候...</span>
              <span v-else>测速完成，已自动选择最快的 5 个节点（可手动调整）</span>
            </template>
          </el-alert>
        </div>

        <el-table
          ref="proxyTableRef"
          :data="sortedProxyNodes"
          border
          max-height="500"
          style="width: 100%"
          @selection-change="handleProxySelectionChange"
          :row-key="(row: any) => row.url">
          <el-table-column
            type="selection"
            width="55"
            :selectable="(row: any) => !benchmarking"
            reserve-selection />
          <el-table-column label="排名" width="80" align="center">
            <template #default="scope">
              <span v-if="getNodeRank(scope.row.url) > 0" style="color: #67C23A; font-weight: bold; font-size: 16px;">
                #{{ getNodeRank(scope.row.url) }}
              </span>
              <span v-else style="color: #909399">-</span>
            </template>
          </el-table-column>
          <el-table-column label="节点" min-width="150">
            <template #default="scope">
              {{ scope.row.label || scope.row.host || '直连' }}
            </template>
          </el-table-column>
          <el-table-column label="地址" min-width="250" show-overflow-tooltip>
            <template #default="scope">
              <span v-if="!scope.row.url">无代理（直连）</span>
              <span v-else>{{ scope.row.url }}</span>
            </template>
          </el-table-column>
          <el-table-column label="测速结果" width="120" align="center">
            <template #default="scope">
              <span v-if="benchmarkResults.get(scope.row.url)">
                {{ formatBenchmarkResult(benchmarkResults.get(scope.row.url)) }}
              </span>
              <span v-else style="color: #909399">-</span>
            </template>
          </el-table-column>
        </el-table>

        <template #footer>
          <div style="display: flex; justify-content: space-between; align-items: center;">
            <span style="color: #909399; font-size: 14px;">
              已选择 {{ selectedProxies.length }} / 5 个节点
            </span>
            <div>
              <el-button @click="smartSelectDialogVisible = false">取消</el-button>
              <el-button
                type="primary"
                @click="confirmProxySelection"
                :disabled="selectedProxies.length === 0 || benchmarking">
                确认选择
              </el-button>
            </div>
          </div>
        </template>
      </el-dialog>

      <el-input v-model="sourceFilter" placeholder="搜索插件名称或地址" clearable style="width: 280px; margin-bottom: 10px"/>

      <div class="table-scroll-wrapper">
        <el-table :data="filteredManagedSources" row-key="id" id="plugins-table" border style="width: 100%; min-width: 1200px" @selection-change="onPluginSelectionChange">
        <el-table-column type="selection" width="55" :selectable="isSourceDeletable"/>
        <el-table-column label="顺序" width="100">
          <template #default="scope">
            <el-input-number
              v-model="scope.row.sortOrder"
              :min="1"
              :max="managedSources.length"
              :controls="false"
              size="small"
              style="width: 60px"
              @change="(val: number) => reorderManagedSource(scope.row.id, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="scope">
            <span>{{ scope.row.builtin ? '内置' : '插件' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" width="180">
          <template #default="scope">
            <el-input v-model="scope.row.name" @change="updateSource(scope.row)"/>
          </template>
        </el-table-column>
        <el-table-column prop="url" label="地址">
          <template #default="scope">
            <a v-if="scope.row.url" :href="scope.row.url" target="_blank">{{ scope.row.url }}</a>
            <span v-else>内置源</span>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="90"/>
        <el-table-column prop="enabled" label="启用" width="90">
          <template #default="scope">
            <el-switch v-model="scope.row.enabled" @change="updateSource(scope.row)"/>
          </template>
        </el-table-column>
        <el-table-column label="最近检查" width="180">
          <template #default="scope">
            <span>{{ formatPluginCheckedAt(scope.row.lastCheckedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastError" label="状态" width="180">
          <template #default="scope">
            <span>{{ scope.row.lastError || '正常' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="scope">
            <el-button v-if="scope.row.extendable" link type="primary" @click="openSourceExtendDialog(scope.row)">配置</el-button>
            <el-button v-if="scope.row.refreshable" link type="primary" @click="refreshPlugin(scope.row.pluginId)">刷新</el-button>
            <el-button v-if="scope.row.deletable" link type="danger" @click="deletePlugin(scope.row.pluginId)">删除</el-button>
            <span v-if="!scope.row.extendable && !scope.row.refreshable && !scope.row.deletable">-</span>
          </template>
        </el-table-column>
      </el-table>
      </div>
    </el-dialog>

    <el-dialog v-model="sourceExtendVisible" title="扩展配置" width="720px">
      <el-input
        v-model="sourceExtendText"
        type="textarea"
        :rows="18"
        placeholder="输入扩展配置文本"
      />
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="sourceExtendVisible = false">取消</el-button>
          <el-button type="primary" @click="saveSourceExtend">保存配置</el-button>
        </span>
      </template>
    </el-dialog>

    <el-dialog v-model="pluginCompilerVisible" title="三方插件编译" width="960px" destroy-on-close>
      <el-collapse class="plugin-compiler-guide">
        <el-collapse-item name="usage">
          <template #title>
            <span>使用说明与示例</span>
          </template>
          <div class="plugin-compiler-guide-body">
            <ol>
              <li>默认使用容器托管的 Ed25519 密钥对和 master secret，只需要把 Python 明文插件粘贴到“插件明文”。内层明文必须是合法 Python，不要写外层 <code>//@name</code> 这类包头。</li>
              <li>容器首次启动会自动生成密钥文件，保存到 <code>/data/secspider</code>；只要 Docker 挂载的 <code>/data</code> 不丢，升级镜像后密钥仍然可用。</li>
              <li>点击“编译”后会生成 <code>secspider/1</code> 插件包，自动保存到 <code>/www/static/self-plugins</code>，并自动导入插件管理列表。</li>
              <li>如果需要更换密钥，使用“重置密钥对”。重置后旧密钥编译的自有插件需要重新编译。</li>
              <li>需要强制只走自有 keyring 时，可在站点扩展配置里设置 <code>secspider_loader: self</code>；默认 <code>auto</code> 会先试原版再试自有。</li>
            </ol>
            <div class="plugin-compiler-example-grid">
              <div>
                <div class="plugin-compiler-example-title">明文插件示例</div>
                <pre class="plugin-compiler-example">from base.spider import Spider

class Spider(Spider):
    def getName(self):
        return "Demo"

    def playerContent(self, flag, id, vipFlags):
        return {"parse": 0, "url": id}</pre>
              </div>
              <div>
                <div class="plugin-compiler-example-title">spiders_v2.json 示例</div>
                <pre class="plugin-compiler-example">[
  {
    "id": "javbus_self",
    "file": "py/javbus_self.txt",
    "version": 1,
    "valid": true
  }
]</pre>
              </div>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
      <el-alert
        v-if="secspiderKeyStatus"
        type="success"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
      >
        <template #title>
          容器托管密钥已就绪：{{ secspiderKeyStatus.keyringPath }}
        </template>
      </el-alert>
      <div class="plugin-compiler-key-actions">
        <el-button type="primary" :loading="secspiderKeyLoading" @click="generateSecspiderKey">
          生成密钥对
        </el-button>
        <el-button type="danger" :loading="secspiderKeyLoading" @click="resetSecspiderKey">
          重置密钥对
        </el-button>
        <span class="plugin-compiler-key-hint">
          私钥仅保存在容器数据目录；编译时后端自动读取，不会回显到页面。
        </span>
      </div>
      <el-form :model="pluginCompilerForm" label-width="120px">
        <el-form-item label="插件名称" required>
          <el-input v-model="pluginCompilerForm.name" placeholder="例如 JavBus"/>
        </el-form-item>
        <el-form-item label="插件版本" required>
          <el-input-number v-model="pluginCompilerForm.version" :min="1" :step="1"/>
        </el-form-item>
        <el-form-item label="插件 ID">
          <el-input v-model="pluginCompilerForm.id" placeholder="稳定 id，可留空"/>
        </el-form-item>
        <el-form-item label="kid">
          <el-input v-model="pluginCompilerForm.kid" placeholder="例如 self-20260716"/>
        </el-form-item>
        <el-form-item label="remark">
          <el-input v-model="pluginCompilerForm.remark" placeholder="可留空"/>
        </el-form-item>
        <el-form-item label="托管密钥">
          <el-switch v-model="pluginCompilerForm.useManagedKey" active-text="使用容器密钥" inactive-text="手动填写"/>
        </el-form-item>
        <el-form-item label="自动导入">
          <el-switch v-model="pluginCompilerForm.autoImport" active-text="编译后导入插件管理" inactive-text="只生成包"/>
        </el-form-item>
        <el-form-item label="插件明文" required>
          <el-input
            v-model="pluginCompilerForm.source"
            type="textarea"
            :rows="9"
            placeholder="粘贴 Python 明文插件源码"
          />
        </el-form-item>
        <el-form-item v-if="!pluginCompilerForm.useManagedKey" label="Ed25519 私钥" required>
          <el-input
            v-model="pluginCompilerForm.privateKey"
            type="textarea"
            :rows="4"
            show-password
            placeholder="PKCS8 PEM/base64，或 32 字节 raw seed 的 base64/hex"
          />
        </el-form-item>
        <el-form-item v-if="!pluginCompilerForm.useManagedKey" label="Ed25519 公钥">
          <el-input
            v-model="pluginCompilerForm.publicKey"
            type="textarea"
            :rows="3"
            placeholder="可选。填写后返回 _self_public_key_chunks"
          />
        </el-form-item>
        <el-form-item v-if="!pluginCompilerForm.useManagedKey" label="master secret" required>
          <el-input
            v-model="pluginCompilerForm.masterSecret"
            type="textarea"
            :rows="2"
            show-password
            placeholder="自有 Atvp.py 中使用的 master secret"
          />
        </el-form-item>
      </el-form>
      <el-alert
        title="默认使用容器托管密钥签名和加密。手动模式下，私钥只随本次请求发送到后端参与签名，接口不会保存或回显私钥。"
        type="warning"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
      />
      <div v-if="pluginCompilerResult">
        <el-descriptions :column="3" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="格式">{{ pluginCompilerResult.format }}</el-descriptions-item>
          <el-descriptions-item label="kid">{{ pluginCompilerResult.kid }}</el-descriptions-item>
          <el-descriptions-item label="大小">{{ pluginCompilerResult.packageSize }}</el-descriptions-item>
          <el-descriptions-item label="明文 SHA256" :span="3">{{ pluginCompilerResult.plainSha256 }}</el-descriptions-item>
          <el-descriptions-item v-if="pluginCompilerResult.importedPluginId" label="已导入插件" :span="3">
            #{{ pluginCompilerResult.importedPluginId }} {{ pluginCompilerResult.importedPluginName }}，
            <a :href="pluginCompilerResult.pluginUrl" target="_blank">{{ pluginCompilerResult.pluginUrl }}</a>
          </el-descriptions-item>
          <el-descriptions-item v-if="pluginCompilerResult.repositoryUrl" label="自用仓库" :span="3">
            <a :href="pluginCompilerResult.repositoryUrl" target="_blank">{{ pluginCompilerResult.repositoryUrl }}</a>
          </el-descriptions-item>
        </el-descriptions>
        <el-tabs v-model="pluginCompilerResultTab">
          <el-tab-pane label="插件包" name="package">
            <el-input v-model="pluginCompilerResult.packageText" type="textarea" :rows="10"/>
            <el-button style="margin-top: 8px" @click="copyUrl(pluginCompilerResult.packageText)">复制插件包</el-button>
          </el-tab-pane>
          <el-tab-pane label="Atvp chunks" name="chunks">
            <el-input :model-value="pluginCompilerChunksText" type="textarea" :rows="10" readonly/>
            <el-button style="margin-top: 8px" @click="copyUrl(pluginCompilerChunksText)">复制 chunks</el-button>
          </el-tab-pane>
        </el-tabs>
      </div>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="pluginCompilerVisible = false">关闭</el-button>
          <el-button type="primary" :loading="pluginCompiling" @click="compilePlugin">编译</el-button>
        </span>
      </template>
    </el-dialog>

    <el-dialog v-model="pluginFilterVisible" title="过滤器管理" fullscreen>
      <el-form :inline="true" :model="pluginFilterForm">
        <el-form-item label="过滤器地址" required>
          <el-input v-model="pluginFilterForm.url" style="width: 460px" placeholder="https://example.com/filter.py"/>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="pluginFilterForm.name" style="width: 180px" placeholder="留空用文件名"/>
        </el-form-item>
        <el-form-item label="拦截点" required>
          <el-select v-model="pluginFilterForm.stageList" multiple collapse-tags style="width: 260px" placeholder="请选择拦截点">
            <el-option v-for="item in filterStageOptions" :key="item.value" :label="item.label" :value="item.value"/>
          </el-select>
        </el-form-item>
        <el-form-item label="作用范围">
          <el-select v-model="pluginFilterForm.pluginScope" style="width: 120px" @change="changePluginFilterScope(pluginFilterForm)">
            <el-option v-for="item in pluginFilterScopeOptions" :key="item.value" :label="item.label" :value="item.value"/>
          </el-select>
        </el-form-item>
        <el-form-item v-if="pluginFilterForm.pluginScope !== 'all'" label="插件" required>
          <el-select
            v-model="pluginFilterForm.pluginIdList"
            multiple
            collapse-tags
            collapse-tags-tooltip
            filterable
            style="width: 260px"
            placeholder="请选择插件"
          >
            <el-option v-for="item in plugins" :key="item.id" :label="pluginOptionLabel(item)" :value="item.id"/>
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="addPluginFilter">添加过滤器</el-button>
        </el-form-item>
      </el-form>

      <el-form :inline="true">
        <el-form-item>
          <el-button type="danger" :disabled="selectedPluginFilterIds.length === 0" @click="deleteSelectedPluginFilters">
            批量删除
          </el-button>
        </el-form-item>
      </el-form>

      <div class="table-scroll-wrapper">
        <el-table
          :data="pluginFilters"
          row-key="id"
          id="plugin-filters-table"
          border
          style="width: 100%; min-width: 1400px"
          @selection-change="onPluginFilterSelectionChange"
      >
        <el-table-column type="selection" width="55"/>
        <el-table-column label="顺序" width="80">
          <template #default="scope">
            <span class="pointer">{{ scope.row.sortOrder }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" width="180">
          <template #default="scope">
            <el-input v-model="scope.row.name" @change="updatePluginFilter(scope.row)"/>
          </template>
        </el-table-column>
        <el-table-column prop="url" label="地址">
          <template #default="scope">
            <a :href="scope.row.url" target="_blank">{{ scope.row.url }}</a>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="90"/>
        <el-table-column prop="enabled" label="启用" width="90">
          <template #default="scope">
            <el-switch v-model="scope.row.enabled" @change="updatePluginFilter(scope.row)"/>
          </template>
        </el-table-column>
        <el-table-column label="拦截点" width="260">
          <template #default="scope">
            <el-select v-model="scope.row.stageList" multiple collapse-tags placeholder="请选择拦截点" @change="updatePluginFilter(scope.row)">
              <el-option v-for="item in filterStageOptions" :key="item.value" :label="item.label" :value="item.value"/>
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="作用范围" width="390">
          <template #default="scope">
            <div class="plugin-filter-scope">
              <el-select v-model="scope.row.pluginScope" style="width: 100px" @change="changePluginFilterScope(scope.row, true)">
                <el-option v-for="item in pluginFilterScopeOptions" :key="item.value" :label="item.label" :value="item.value"/>
              </el-select>
              <el-select
                v-if="scope.row.pluginScope !== 'all'"
                v-model="scope.row.pluginIdList"
                multiple
                collapse-tags
                collapse-tags-tooltip
                filterable
                style="width: 250px"
                placeholder="请选择插件"
                @change="updatePluginFilter(scope.row)"
              >
                <el-option v-for="item in plugins" :key="item.id" :label="pluginOptionLabel(item)" :value="item.id"/>
              </el-select>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="errorStrategy" label="错误策略" width="120">
          <template #default="scope">
            <el-select v-model="scope.row.errorStrategy" @change="updatePluginFilter(scope.row)">
              <el-option label="跳过" value="skip"/>
              <el-option label="中断" value="strict"/>
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="最近检查" width="180">
          <template #default="scope">
            <span>{{ formatPluginCheckedAt(scope.row.lastCheckedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastError" label="状态" width="180">
          <template #default="scope">
            <span>{{ scope.row.lastError || '正常' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="scope">
            <el-button link type="primary" @click="openPluginFilterConfig(scope.row)">配置</el-button>
            <el-button link type="primary" @click="refreshPluginFilter(scope.row.id)">刷新</el-button>
            <el-button link type="danger" @click="deletePluginFilter(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      </div>
    </el-dialog>

    <el-dialog v-model="pluginFilterConfigVisible" title="过滤器配置" width="860px" destroy-on-close>
      <div v-if="pluginFilterConfigTarget" class="filter-config-dialog">
        <div class="filter-config-header">
          <div class="filter-config-title">{{ pluginFilterConfigTarget.name || pluginFilterConfigTarget.sourceName || pluginFilterConfigTarget.url }}</div>
          <div class="filter-config-subtitle">
            <span v-if="pluginFilterConfigSchema.description">{{ pluginFilterConfigSchema.description }}</span>
            <span v-if="pluginFilterConfigSchema.source">来源：{{ pluginFilterSchemaSourceLabel(pluginFilterConfigSchema.source) }}</span>
          </div>
        </div>

        <el-alert
          v-if="pluginFilterConfigError"
          type="warning"
          :closable="false"
          :title="pluginFilterConfigError"
          style="margin-bottom: 12px"
        />

        <el-tabs v-model="pluginFilterConfigMode">
          <el-tab-pane label="表单编辑" name="form">
            <div v-if="pluginFilterConfigSchema.fields.length" class="filter-config-form">
              <PluginFilterConfigFieldEditor
                v-for="field in pluginFilterConfigSchema.fields"
                :key="field.key"
                :field="field"
                :model-value="pluginFilterConfigObject"
                @update:model-value="onPluginFilterConfigObjectChange"
              />
            </div>
            <el-empty v-else description="未识别到预设字段，可直接在 JSON 模式编辑"/>

            <div class="filter-config-extra">
              <div class="filter-config-extra-header">
                <span>额外字段</span>
                <el-button v-if="pluginFilterConfigSchema.allowAdditional" link type="primary" @click="addPluginFilterExtraEntry">添加字段</el-button>
              </div>
              <div v-if="pluginFilterConfigExtras.length" class="filter-config-extra-list">
                <div v-for="(item, index) in pluginFilterConfigExtras" :key="`${index}-${item.key}`" class="filter-config-extra-row">
                  <el-input v-model="item.key" placeholder="key"/>
                  <el-input v-model="item.value" placeholder="value / JSON"/>
                  <el-button link type="danger" @click="removePluginFilterExtraEntry(index)">删除</el-button>
                </div>
              </div>
              <div v-else-if="!pluginFilterConfigSchema.allowAdditional" class="filter-config-extra-empty">当前过滤器未开放额外字段，建议只填写已声明的配置项。</div>
              <div v-else class="filter-config-extra-empty">如果过滤器支持更多自定义参数，可以在这里补充声明外的字段。</div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="JSON 编辑" name="json">
            <el-input
              v-model="pluginFilterConfigJson"
              type="textarea"
              :rows="22"
              placeholder="{&#10;  &quot;key&quot;: &quot;value&quot;&#10;}"
            />
          </el-tab-pane>
        </el-tabs>
      </div>

      <template #footer>
        <span class="dialog-footer">
          <el-button @click="pluginFilterConfigVisible = false">取消</el-button>
          <el-button @click="syncPluginFilterConfigJsonFromForm">同步到 JSON</el-button>
          <el-button type="primary" @click="savePluginFilterConfig">保存配置</el-button>
        </span>
      </template>
    </el-dialog>

    <el-dialog v-model="editorVisible" :title="editorTargetIsGlobal ? '全局订阅配置' : '订阅定制'" width="900px" destroy-on-close>
      <div style="margin-bottom: 8px; display: flex; align-items: center; justify-content: space-between">
        <span v-if="editorTargetIsGlobal">参考订阅：
          <el-select v-model="globalReferenceSid" style="width: 220px">
            <el-option v-for="item in subscriptions" :key="item.sid" :label="item.name" :value="item.sid" />
          </el-select>
        </span>
        <span v-else />
        <el-link type="info" href="https://github.com/FongMi/TV/blob/release/docs/CONFIG.md" target="_blank" :underline="false">
          配置文档 <el-icon><Link /></el-icon>
        </el-link>
      </div>
      <SubscriptionConfigEditor
        ref="editorRef"
        :model-value="editorTargetIsGlobal ? globalConfigJson : form.override"
        :mode="editorTargetIsGlobal ? 'global' : 'subscription'"
        :reference-sid="editorTargetIsGlobal ? globalReferenceSid : form.sid"
        :token="token"
      />
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="editorVisible = false">取消</el-button>
          <el-button type="primary" @click="saveEditor">{{ editorTargetIsGlobal ? '保存全局' : '应用到定制' }}</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- JSON 预览确认 -->
    <el-dialog v-model="jsonPreviewVisible" title="确认保存" width="700px" append-to-body>
      <el-alert type="info" :closable="false" style="margin-bottom: 8px">
        <template #title>请确认以下配置内容无误后点击「确认保存」</template>
      </el-alert>
      <el-input v-model="jsonPreviewText" type="textarea" :rows="16" readonly style="font-family: monospace" />
      <template #footer>
        <el-button @click="jsonPreviewVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmSaveEditor">确认保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rawJsonEditorVisible" title="JSON 编辑" width="700px">
      <el-input
        v-model="rawJsonEditorText"
        type="textarea"
        :rows="20"
        placeholder="输入 JSON 格式的订阅定制配置"
      />
      <template #footer>
        <el-button @click="rawJsonEditorVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRawJson">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="addProxyDialogVisible" title="添加自定义 GitHub 代理" width="700px">
      <el-form label-width="120px">
        <el-form-item label="代理地址">
          <el-input
            v-model="customProxyUrls"
            type="textarea"
            :rows="8"
            placeholder="每行一个代理地址，例如：&#10;https://gh.llkk.cc/&#10;https://github.starrlzy.cn/&#10;gh.tryxd.cn&#10;&#10;支持自动补全协议和斜杠"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item>
          <el-alert type="info" :closable="false">
            <template #title>
              <span style="font-size: 13px;">
                • 每行一个代理地址<br/>
                • 自动添加 https:// 协议（如果缺少）<br/>
                • 自动添加末尾斜杠（如果缺少）<br/>
                • 空行和重复地址会自动过滤
              </span>
            </template>
          </el-alert>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addProxyDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="addCustomProxiesToList">添加并测速</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import {computed, nextTick, onMounted, onUnmounted, ref, watch} from 'vue'
import axios from "axios"
import clipBorad from "vue-clipboard3";
import api from "@/utils/api"
import {ElMessage} from "element-plus";
import {Link, ArrowRight} from "@element-plus/icons-vue";
import Sortable from "sortablejs";
import type {Device} from "@/model/Device";
import PluginFilterConfigFieldEditor from "@/components/PluginFilterConfigFieldEditor.vue";
import SubscriptionConfigEditor from "@/components/SubscriptionConfigEditor.vue";
import {isPluginDragEnabledForUserAgent} from "@/utils/pluginDragSupport.mjs";

interface Sub {
  sid: '',
  name: '',
}

interface Plugin {
  id: number
  name: string
  url: string
  enabled: boolean
  sortOrder: number
  version: number | null
  extend: string
  sourceName: string
  lastCheckedAt: string
  lastError: string
}

interface ManagedSource {
  id: string
  builtin: boolean
  pluginId: number | null
  key: string
  name: string
  sourceName: string
  url: string
  enabled: boolean
  sortOrder: number
  version: number | null
  extend: string
  lastCheckedAt: string
  lastError: string
  deletable: boolean
  refreshable: boolean
  extendable: boolean
}

interface PluginFilter {
  id: number
  name: string
  url: string
  enabled: boolean
  sortOrder: number
  version: number | null
  stages: string
  stageList: string[]
  extend: string
  errorStrategy: string
  pluginScope: string
  pluginIds: string
  pluginIdList: number[]
  sourceName: string
  lastCheckedAt: string
  lastError: string
  configSchema?: PluginFilterConfigSchema
}

interface PluginFilterConfigField {
  key: string
  label: string
  type: string
  required: boolean
  description: string
  defaultValue?: any
  placeholder: string
  aliases: string[]
  children: PluginFilterConfigField[]
}

interface PluginFilterConfigSchema {
  source: string
  description: string
  allowAdditional: boolean
  singleValueKey: string
  example: string
  fields: PluginFilterConfigField[]
}

interface PluginFilterExtraEntry {
  key: string
  value: string
}

interface PluginCompileForm {
  name: string
  version: number
  remark: string
  id: string
  kid: string
  source: string
  privateKey: string
  publicKey: string
  masterSecret: string
  useManagedKey: boolean
  autoImport: boolean
}

interface PluginCompileResult {
  packageText: string
  plainSha256: string
  packageSize: number
  format: string
  alg: string
  wrap: string
  sign: string
  kid: string
  publicKeyChunks: string[]
  masterSecretChunks: string[]
  importedPluginId: number | null
  importedPluginName: string
  pluginUrl: string
  repositoryUrl: string
  localPath: string
}

interface SecspiderKeyStatus {
  generated: boolean
  privateKeyPath: string
  publicKeyPath: string
  masterSecretPath: string
  keyringPath: string
  publicKey: string
  publicKeyChunks: string[]
  masterSecretChunks: string[]
}

const PLUGIN_REPO_URL_KEY = 'plugin_repo_url'
const currentUrl = window.location.origin
const filterStageOptions = [
  {label: '详情', value: 'detail'},
  {label: '解析详情', value: 'parse'},
  {label: '播放', value: 'player'},
  {label: '后端播放', value: 'play'},
  {label: '弹幕', value: 'danmaku'},
]
const pluginFilterScopeOptions = [
  {label: '全局', value: 'all'},
  {label: '仅对', value: 'include'},
  {label: '除外', value: 'exclude'},
]
const pluginRunModeOptions = [
  {label: 'Java代理', value: 'java'},
  {label: '原生Python', value: 'python'},
]
const tgPhase = ref(0)
const tgPhone = ref('')
const tgCode = ref('')
const tgPassword = ref('')
const tgAuthType = ref('qr')
const base64QrCode = ref('')
const enabledToken = ref(false)
const selectedToken = ref('')
const token = computed(() => enabledToken.value && selectedToken.value ? '/' + selectedToken.value : '')
const basicAuthUser = ref('')
const basicAuthPass = ref('')
function withBasicAuth(base: string) {
  if (!basicAuthUser.value && !basicAuthPass.value) return base
  const prefix = basicAuthUser.value + ':' + basicAuthPass.value + '@'
  return base.replace('http://', 'http://' + prefix).replace('https://', 'https://' + prefix)
}
const openUrl = computed(() => withBasicAuth(currentUrl) + '/open' + token.value)
const nodeUrl = computed(() => withBasicAuth(currentUrl) + '/node' + (token.value ? token.value : '/-') + '/index.config.js')
const nodeUrl2 = computed(() => withBasicAuth(currentUrl) + '/node' + (token.value ? token.value : '/-') + '/index.js.md5')

let {toClipboard} = clipBorad();

function copyUrl(text: string) {
  toClipboard(text).then(() => {
    ElMessage.success('已复制')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}
const pgLocal = ref('')
const pgRemote = ref('')
const zxLocal = ref('')
const zxRemote = ref('')
const zxLocal2 = ref('')
const zxRemote2 = ref('')
const xsLocal = ref('')
const xsRemote = ref('')
const updateAction = ref(false)
const dialogTitle = ref('')
const jsonData = ref({})
const subscriptions = ref<Sub[]>([])
const loading = ref(false)
const tokens = ref([])
const devices = ref<Device[]>([])
const loadingDevices = ref(false)
const detailVisible = ref(false)
const detailTab = ref('json')
const rawJsonData = computed(() => JSON.stringify(jsonData.value, null, 2))
const formVisible = ref(false)
const dialogVisible = ref(false)
const pluginVisible = ref(false)
const pluginCompilerVisible = ref(false)
const pluginFilterVisible = ref(false)
const pluginFilterConfigVisible = ref(false)
const sourceExtendVisible = ref(false)
const importingPlugins = ref(false)
const pluginCompiling = ref(false)
const secspiderKeyLoading = ref(false)
const secspiderKeyStatus = ref<SecspiderKeyStatus | null>(null)
const tgVisible = ref(false)
const scanVisible = ref(false)
const confirm = ref(false)
const jsonPreviewVisible = ref(false)
const jsonPreviewText = ref('')
const push = ref(false)
const device = ref<Device>({
  name: "",
  type: "",
  uuid: "",
  id: 0,
  ip: ''
})
const pushForm = ref({
  id: 0,
  sid: '',
  token: '',
  name: '',
  url: '',
})
const sub = ref({
  name: "",
  sid: "",
})
const form = ref({
  id: 0,
  sid: '',
  name: '',
  url: '',
  sort: '',
  override: ''
})
const user = ref({
  id: 0,
  username: '',
  first_name: '',
  last_name: '',
  phone: ''
})
const globalConfigJson = ref('')
const editorVisible = ref(false)
const editorRef = ref<any>(null)
const editorTargetIsGlobal = ref(false)
const globalReferenceSid = ref('')
const rawJsonEditorVisible = ref(false)
const rawJsonEditorText = ref('')
const plugins = ref<Plugin[]>([])
const managedSources = ref<ManagedSource[]>([])
const sourceFilter = ref('')
const filteredManagedSources = computed(() => {
  const keyword = sourceFilter.value.trim().toLowerCase()
  if (!keyword) return managedSources.value
  return managedSources.value.filter(item =>
    (item.name && item.name.toLowerCase().includes(keyword)) ||
    (item.url && item.url.toLowerCase().includes(keyword))
  )
})

const isBenchmarkPending = (result: any) => result?.success === null || result?.pending

// 计算按测速结果排序的代理节点列表
const getSortedProxyNodes = () => {
  const nodes = [...githubProxyNodes.value]

  return nodes.sort((a, b) => {
    const resultA = benchmarkResults.value.get(a.url)
    const resultB = benchmarkResults.value.get(b.url)
    const pendingA = isBenchmarkPending(resultA)
    const pendingB = isBenchmarkPending(resultB)

    // 都没有结果，保持原顺序
    if (!resultA && !resultB) return 0
    if (!resultA) return 1
    if (!resultB) return -1

    // 失败的排在最后
    if (!resultA.success && !pendingA && resultB.success) return 1
    if (resultA.success && !resultB.success && !pendingB) return -1

    // 测速中的排在中间（成功之后，失败之前）
    if (pendingA && !pendingB) {
      return resultB.success ? 1 : -1  // 如果B成功，A排后面；如果B失败，A排前面
    }
    if (!pendingA && pendingB) {
      return resultA.success ? -1 : 1  // 如果A成功，A排前面；如果A失败，A排后面
    }
    if (pendingA && pendingB) return 0

    // 都成功，按延迟排序
    if (resultA.success && resultB.success) {
      return (resultA.latency || 0) - (resultB.latency || 0)
    }

    // 都失败，保持原顺序
    return 0
  })
}

// 排序后的代理节点列表（按测速结果排序）
const sortedProxyNodes = computed(() => getSortedProxyNodes())

const pluginFilters = ref<PluginFilter[]>([])
const pluginForm = ref<Plugin>({
  id: 0,
  name: '',
  url: '',
  enabled: true,
  sortOrder: 0,
  version: null,
  extend: '',
  sourceName: '',
  lastCheckedAt: '',
  lastError: ''
})
const pluginImportForm = ref({
  url: localStorage.getItem(PLUGIN_REPO_URL_KEY) || ''
})
const pluginCompilerForm = ref<PluginCompileForm>({
  name: '',
  version: 1,
  remark: '',
  id: '',
  kid: 'self',
  source: '',
  privateKey: '',
  publicKey: '',
  masterSecret: '',
  useManagedKey: true,
  autoImport: true
})
const pluginCompilerResult = ref<PluginCompileResult | null>(null)
const pluginCompilerResultTab = ref('package')
const pluginCompilerChunksText = computed(() => {
  if (!pluginCompilerResult.value) {
    return ''
  }
  const publicKeyChunks = JSON.stringify(pluginCompilerResult.value.publicKeyChunks || [], null, 2)
  const masterSecretChunks = JSON.stringify(pluginCompilerResult.value.masterSecretChunks || [], null, 2)
  return `_self_public_key_chunks = ${publicKeyChunks}\n_self_master_secret_chunks = ${masterSecretChunks}`
})
const pluginSettingsForm = ref({
  githubProxy: '',
  pluginRunMode: 'java'
})
const githubProxyNodes = ref<any[]>([])
const githubProxyList = ref<string[]>([])
const githubProxyCollapseActive = ref<string[]>([]) // 默认折叠
const benchmarking = ref(false)
const smartSelectDialogVisible = ref(false)
const selectedProxies = ref<string[]>([])
const proxyTableRef = ref()
const benchmarkResults = ref<Map<string, any>>(new Map())
const addProxyDialogVisible = ref(false)
const newProxyUrl = ref('')
const customProxyUrls = ref('')
const selectedPluginIds = ref<number[]>([])
const sourceExtendTarget = ref<ManagedSource | null>(null)
const sourceExtendText = ref('')
const pluginFilterForm = ref<PluginFilter>({
  id: 0,
  name: '',
  url: '',
  enabled: true,
  sortOrder: 0,
  version: null,
  stages: '',
  stageList: [],
  extend: '',
  errorStrategy: 'skip',
  pluginScope: 'all',
  pluginIds: '',
  pluginIdList: [],
  sourceName: '',
  lastCheckedAt: '',
  lastError: '',
  configSchema: undefined
})
const selectedPluginFilterIds = ref<number[]>([])
const pluginFilterConfigTarget = ref<PluginFilter | null>(null)
const pluginFilterConfigSchema = ref<PluginFilterConfigSchema>({
  source: 'none',
  description: '',
  allowAdditional: true,
  singleValueKey: '',
  example: '',
  fields: []
})
const pluginFilterConfigMode = ref('form')
const pluginFilterConfigJson = ref('{}')
const pluginFilterConfigObject = ref<Record<string, any>>({})
const pluginFilterConfigExtras = ref<PluginFilterExtraEntry[]>([])
const pluginFilterConfigError = ref('')
const pluginDragEnabled = ref(isPluginDragEnabledForUserAgent(window.navigator.userAgent))
let timer = 0
let pluginSortable: Sortable | null = null
let pluginFilterSortable: Sortable | null = null

const handleLogin = () => {
  axios.get('/api/telegram/user').then(({data}) => {
    user.value = data
  })
  axios.get('/api/settings/tg_auth_type').then(({data}) => {
    tgAuthType.value = data.value || 'qr'
  })
  tgVisible.value = true
}

const login = () => {
  axios.post('/api/telegram/login')
  timer = setInterval(() => {
    axios.get('/api/settings/tg_phase').then(({data}) => {
      tgPhase.value = +data.value
      if (tgPhase.value > 8) {
        clearInterval(timer)
        axios.get('/api/telegram/user').then(({data}) => {
          user.value = data
        })
      } else if (tgAuthType.value == 'qr' && tgPhase.value == 1 && !base64QrCode.value) {
        loadQrCode()
      }
    })
  }, 1000)
  setTimeout(() => {
    clearInterval(timer)
  }, 120_000)
}

const loadQrCode = () => {
  axios.get('/api/settings/tg_qr_img').then(({data}) => {
    base64QrCode.value = data.value
  })
}

const cancelLogin = () => {
  clearInterval(timer)
  tgVisible.value = false
}

const reset = () => {
  axios.post('/api/telegram/reset').then(() => {
    ElMessage.success('重置成功')
    clearInterval(timer)
  })
}

const logout = () => {
  axios.post('/api/telegram/logout').then(() => {
    ElMessage.success('退出登陆成功')
    clearInterval(timer)
    user.value = {
      id: 0,
      username: '',
      first_name: '',
      last_name: '',
      phone: ''
    }
  })
}

const handleAdd = () => {
  dialogTitle.value = '添加订阅'
  updateAction.value = false
  form.value = {
    id: 0,
    sid: '',
    name: '',
    url: '',
    sort: '',
    override: ''
  }
  formVisible.value = true
}

const resetPluginForm = () => {
  pluginForm.value = {
    id: 0,
    name: '',
    url: '',
    enabled: true,
    sortOrder: 0,
    version: null,
    extend: '',
    sourceName: '',
    lastCheckedAt: '',
    lastError: ''
  }
}

const resetPluginCompilerForm = () => {
  pluginCompilerForm.value = {
    name: '',
    version: 1,
    remark: '',
    id: '',
    kid: 'self',
    source: '',
    privateKey: '',
    publicKey: '',
    masterSecret: '',
    useManagedKey: true,
    autoImport: true
  }
  pluginCompilerResult.value = null
  pluginCompilerResultTab.value = 'package'
}

const parsePluginFilterStages = (value: string) => {
  return (value || '')
    .split(',')
    .map(item => item.trim())
    .filter(item => item)
}

const parsePluginFilterPluginIds = (value: string) => {
  return (value || '')
    .split(',')
    .map(item => Number(item.trim()))
    .filter(item => Number.isInteger(item) && item > 0)
}

const normalizePluginFilterScope = (value: string) => {
  return value === 'include' || value === 'exclude' ? value : 'all'
}

const normalizePluginFilter = (value: PluginFilter) => {
  const pluginScope = normalizePluginFilterScope(value.pluginScope)
  return {
    ...value,
    stages: value.stages || '',
    stageList: value.stageList || parsePluginFilterStages(value.stages),
    // 全局模式不需要保留选择器值，避免界面显示历史残留
    pluginScope,
    pluginIds: value.pluginIds || '',
    pluginIdList: pluginScope === 'all' ? [] : value.pluginIdList || parsePluginFilterPluginIds(value.pluginIds),
    errorStrategy: value.errorStrategy || 'skip',
    configSchema: normalizePluginFilterConfigSchema(value.configSchema)
  }
}

const normalizePluginFilterConfigSchema = (schema?: PluginFilterConfigSchema): PluginFilterConfigSchema => {
  return {
    source: schema?.source || 'none',
    description: schema?.description || '',
    allowAdditional: schema?.allowAdditional !== false,
    singleValueKey: schema?.singleValueKey || '',
    example: schema?.example || '',
    fields: Array.isArray(schema?.fields)
      ? schema!.fields.map(field => normalizePluginFilterConfigField(field))
      : []
  }
}

const pluginFilterSchemaSourceLabel = (source: string) => {
  if (source === 'declared') {
    return '过滤器脚本声明'
  }
  if (source === 'none') {
    return '未声明'
  }
  return source || '未知'
}

const normalizePluginFilterConfigField = (field: PluginFilterConfigField): PluginFilterConfigField => {
  return {
    key: field?.key || '',
    label: field?.label || '',
    type: field?.type || 'string',
    required: !!field?.required,
    description: field?.description || '',
    defaultValue: field?.defaultValue,
    placeholder: field?.placeholder || '',
    aliases: Array.isArray(field?.aliases) ? field.aliases : [],
    children: Array.isArray(field?.children) ? field.children.map(item => normalizePluginFilterConfigField(item)) : []
  }
}

const getPluginFilterStageList = (filter: PluginFilter) => {
  if (Array.isArray(filter.stageList)) {
    return filter.stageList.map(item => item.trim()).filter(item => item)
  }
  return parsePluginFilterStages(filter.stages)
}

const getPluginFilterPluginIdList = (filter: PluginFilter) => {
  if (Array.isArray(filter.pluginIdList)) {
    return filter.pluginIdList
      .map(item => Number(item))
      .filter(item => Number.isInteger(item) && item > 0)
  }
  return parsePluginFilterPluginIds(filter.pluginIds)
}

const validatePluginFilter = (filter: PluginFilter) => {
  if (!filter.url.trim()) {
    ElMessage.warning('请输入过滤器地址')
    return false
  }
  if (getPluginFilterStageList(filter).length === 0) {
    ElMessage.warning('请选择拦截点')
    return false
  }
  if (normalizePluginFilterScope(filter.pluginScope) !== 'all' && getPluginFilterPluginIdList(filter).length === 0) {
    ElMessage.warning('请选择关联插件')
    return false
  }
  return true
}

const pluginFilterPayload = (filter: PluginFilter) => {
  const stageList = getPluginFilterStageList(filter)
  const pluginScope = normalizePluginFilterScope(filter.pluginScope)
  // 只把当前模式需要的插件 ID 回写给后端
  const pluginIdList = pluginScope === 'all' ? [] : getPluginFilterPluginIdList(filter)
  const payload = {
    name: filter.name,
    url: filter.url,
    enabled: filter.enabled,
    sortOrder: filter.sortOrder,
    stages: stageList.join(','),
    extend: filter.extend,
    errorStrategy: filter.errorStrategy,
    pluginScope,
    pluginIds: pluginIdList.join(',')
  }
  if (filter.id) {
    return {
      ...payload,
      id: filter.id
    }
  }
  return payload
}

const resetPluginFilterForm = () => {
  pluginFilterForm.value = {
    id: 0,
    name: '',
    url: '',
    enabled: true,
    sortOrder: 0,
    version: null,
    stages: '',
    stageList: [],
    extend: '',
    errorStrategy: 'skip',
    pluginScope: 'all',
    pluginIds: '',
    pluginIdList: [],
    sourceName: '',
    lastCheckedAt: '',
    lastError: '',
    configSchema: undefined
  }
}

const parsePluginFilterExtend = (extend: string) => {
  const text = (extend || '').trim()
  if (!text) {
    return {}
  }
  try {
    const parsed = JSON.parse(text)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return null
  }
}

const stringifyPluginFilterExtend = (value: Record<string, any>) => {
  const keys = Object.keys(value).filter(key => key && value[key] !== undefined && value[key] !== '')
  if (keys.length === 0) {
    return ''
  }
  return JSON.stringify(value, null, 2)
}

const cloneJsonValue = <T>(value: T): T => JSON.parse(JSON.stringify(value))

const getDeclaredFieldKeys = (schema: PluginFilterConfigSchema) => {
  const keys = schema.fields.flatMap(field => [field.key, ...(field.aliases || [])])
  return keys.filter(key => key)
}

const onPluginFilterConfigObjectChange = (value: Record<string, any>) => {
  pluginFilterConfigObject.value = value
}

const getObjectAtPath = (root: Record<string, any>, path: string[]) => {
  let current: any = root
  for (const segment of path) {
    if (!current || typeof current !== 'object' || Array.isArray(current)) {
      return {}
    }
    current = current[segment]
  }
  return current && typeof current === 'object' && !Array.isArray(current) ? current : {}
}

const fieldValueByAliases = (field: PluginFilterConfigField, container: Record<string, any>) => {
  const keys = [field.key, ...(field.aliases || [])].filter(Boolean)
  for (const key of keys) {
    const value = container[key]
    if (value !== undefined && value !== null) {
      return value
    }
  }
  return field.defaultValue
}

const rebuildPluginFilterConfigExtras = () => {
  const declared = new Set(getDeclaredFieldKeys(pluginFilterConfigSchema.value))
  pluginFilterConfigExtras.value = Object.entries(pluginFilterConfigObject.value)
    .filter(([key]) => !declared.has(key))
    .map(([key, value]) => ({
      key,
      value: typeof value === 'string' ? value : JSON.stringify(value)
    }))
}

const validatePluginFilterConfigObject = () => {
  const validateFields = (fields: PluginFilterConfigField[], container: Record<string, any>, path: string[] = []): string => {
    for (const field of fields) {
      const currentPath = [...path, field.label || field.key]
      const value = fieldValueByAliases(field, container)
      if (field.type === 'object' && field.children?.length) {
        const nested = value && typeof value === 'object' && !Array.isArray(value) ? value : {}
        const hasNestedValue = Object.keys(nested).length > 0
        if (field.required && !hasNestedValue) {
          return currentPath.join(' / ')
        }
        const nestedRequiredPath = validateFields(field.children, nested, currentPath)
        if (nestedRequiredPath) {
          return nestedRequiredPath
        }
        continue
      }
      if (!field.required) {
        continue
      }
      if (value === undefined || value === null || value === '') {
        return currentPath.join(' / ')
      }
    }
    return ''
  }

  const invalidPath = validateFields(pluginFilterConfigSchema.value.fields, pluginFilterConfigObject.value)
  if (invalidPath) {
    ElMessage.warning(`请填写 ${invalidPath}`)
    return false
  }
  return true
}

const syncPluginFilterConfigJsonFromForm = () => {
  const declared = new Set(getDeclaredFieldKeys(pluginFilterConfigSchema.value))
  const nextValue: Record<string, any> = {}
  for (const field of pluginFilterConfigSchema.value.fields) {
    const value = fieldValueByAliases(field, pluginFilterConfigObject.value)
    if (value !== undefined && value !== null && value !== '') {
      nextValue[field.key] = cloneJsonValue(value)
    }
  }
  for (const item of pluginFilterConfigExtras.value) {
    const key = (item.key || '').trim()
    if (!key || declared.has(key)) {
      continue
    }
    const raw = (item.value || '').trim()
    if (!raw) {
      continue
    }
    try {
      nextValue[key] = JSON.parse(raw)
    } catch {
      nextValue[key] = raw
    }
  }
  pluginFilterConfigObject.value = nextValue
  pluginFilterConfigJson.value = stringifyPluginFilterExtend(nextValue) || '{}'
  pluginFilterConfigError.value = ''
}

const syncPluginFilterConfigFormFromJson = () => {
  try {
    const parsed = pluginFilterConfigJson.value.trim() ? JSON.parse(pluginFilterConfigJson.value) : {}
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      pluginFilterConfigError.value = '配置必须是 JSON 对象'
      return false
    }
    pluginFilterConfigObject.value = cloneJsonValue(parsed)
    rebuildPluginFilterConfigExtras()
    pluginFilterConfigError.value = ''
    return true
  } catch {
    pluginFilterConfigError.value = 'JSON 格式不正确，请先修正后再保存'
    return false
  }
}

const addPluginFilterExtraEntry = () => {
  pluginFilterConfigExtras.value.push({key: '', value: ''})
}

const removePluginFilterExtraEntry = (index: number) => {
  pluginFilterConfigExtras.value.splice(index, 1)
}

const ensurePluginFilterConfigSchema = async (filter: PluginFilter) => {
  if (filter.configSchema?.fields?.length || filter.configSchema?.description) {
    pluginFilterConfigSchema.value = normalizePluginFilterConfigSchema(filter.configSchema)
    return
  }
  if (!filter.id) {
    pluginFilterConfigSchema.value = normalizePluginFilterConfigSchema(undefined)
    return
  }
  const {data} = await axios.get(`/api/plugin-filters/${filter.id}/config-schema`)
  filter.configSchema = normalizePluginFilterConfigSchema(data)
  pluginFilterConfigSchema.value = filter.configSchema
}

const openPluginFilterConfig = async (filter: PluginFilter) => {
  pluginFilterConfigTarget.value = filter
  pluginFilterConfigVisible.value = true
  pluginFilterConfigMode.value = 'form'
  pluginFilterConfigError.value = ''
  await ensurePluginFilterConfigSchema(filter)
  const parsed = parsePluginFilterExtend(filter.extend)
  if (parsed === null) {
    if (pluginFilterConfigSchema.value.singleValueKey) {
      pluginFilterConfigObject.value = {
        [pluginFilterConfigSchema.value.singleValueKey]: filter.extend
      }
      pluginFilterConfigJson.value = stringifyPluginFilterExtend(pluginFilterConfigObject.value) || '{}'
      rebuildPluginFilterConfigExtras()
      pluginFilterConfigError.value = '已将纯文本配置自动映射为表单字段。'
      return
    }
    pluginFilterConfigObject.value = {}
    pluginFilterConfigJson.value = filter.extend || ''
    pluginFilterConfigMode.value = 'json'
    pluginFilterConfigError.value = '当前扩展配置不是标准 JSON，对话框已切换到 JSON 模式。'
    pluginFilterConfigExtras.value = []
    return
  }
  pluginFilterConfigObject.value = cloneJsonValue(parsed)
  pluginFilterConfigJson.value = stringifyPluginFilterExtend(parsed) || '{}'
  rebuildPluginFilterConfigExtras()
}

const savePluginFilterConfig = async () => {
  if (!pluginFilterConfigTarget.value) {
    return
  }
  if (pluginFilterConfigMode.value === 'json' && !syncPluginFilterConfigFormFromJson()) {
    return
  }
  if (!validatePluginFilterConfigObject()) {
    return
  }
  if (pluginFilterConfigMode.value === 'form') {
    syncPluginFilterConfigJsonFromForm()
  } else {
    pluginFilterConfigJson.value = stringifyPluginFilterExtend(pluginFilterConfigObject.value) || '{}'
  }
  pluginFilterConfigTarget.value.extend = pluginFilterConfigJson.value === '{}' ? '' : pluginFilterConfigJson.value
  updatePluginFilter(pluginFilterConfigTarget.value)
  pluginFilterConfigVisible.value = false
}

const changePluginFilterScope = (filter: PluginFilter, save = false) => {
  filter.pluginScope = normalizePluginFilterScope(filter.pluginScope)
  if (filter.pluginScope === 'all') {
    filter.pluginIdList = []
    if (save) {
      updatePluginFilter(filter)
    }
    return
  }
  // 列表行切换到仅对/除外时，先等用户选完插件再保存，避免立即触发校验提示
  if (save && getPluginFilterPluginIdList(filter).length > 0) {
    updatePluginFilter(filter)
  }
}

const pluginOptionLabel = (plugin: Plugin) => {
  return `${plugin.name || plugin.sourceName || plugin.url} #${plugin.id}`
}

const padTimePart = (value: number) => value.toString().padStart(2, '0')

const formatPluginCheckedAt = (value: string) => {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value.replace('T', ' ').replace(/(?:Z|[+-]\d{2}:\d{2})$/, '')
  }
  return `${date.getFullYear()}-${padTimePart(date.getMonth() + 1)}-${padTimePart(date.getDate())} ${padTimePart(date.getHours())}:${padTimePart(date.getMinutes())}:${padTimePart(date.getSeconds())}`
}

const enablePluginRowDrop = () => {
  if (!pluginDragEnabled.value) {
    pluginSortable?.destroy()
    pluginSortable = null
    return
  }
  const tbody = document.querySelector('#plugins-table .el-table__body-wrapper tbody') as HTMLElement
  if (!tbody) {
    return
  }
  pluginSortable?.destroy()
  pluginSortable = Sortable.create(tbody, {
    animation: 300,
    draggable: '.el-table__row',
    onEnd: ({oldIndex, newIndex}) => {
      if (oldIndex == null || newIndex == null || oldIndex === newIndex) {
        return
      }
      const row = managedSources.value.splice(oldIndex, 1)[0]
      managedSources.value.splice(newIndex, 0, row)
      managedSources.value.forEach((item, index) => {
        item.sortOrder = index + 1
      })
      axios.post('/api/subscription-sources/reorder', managedSources.value.map(item => item.id)).then(() => {
        ElMessage.success('排序已更新')
        loadManagedSources()
      })
    }
  })
}

const enablePluginFilterRowDrop = () => {
  if (!pluginDragEnabled.value) {
    pluginFilterSortable?.destroy()
    pluginFilterSortable = null
    return
  }
  const tbody = document.querySelector('#plugin-filters-table .el-table__body-wrapper tbody') as HTMLElement
  if (!tbody) {
    return
  }
  pluginFilterSortable?.destroy()
  pluginFilterSortable = Sortable.create(tbody, {
    animation: 300,
    draggable: '.el-table__row',
    onEnd: ({oldIndex, newIndex}) => {
      if (oldIndex == null || newIndex == null || oldIndex === newIndex) {
        return
      }
      const row = pluginFilters.value.splice(oldIndex, 1)[0]
      pluginFilters.value.splice(newIndex, 0, row)
      pluginFilters.value.forEach((item, index) => {
        item.sortOrder = index + 1
      })
      axios.post('/api/plugin-filters/reorder', pluginFilters.value.map(item => item.id)).then(() => {
        ElMessage.success('排序已更新')
        loadPluginFilters()
      })
    }
  })
}

const loadPlugins = () => {
  axios.get('/api/plugins').then(({data}) => {
    plugins.value = data
  })
}

const reorderManagedSource = (sourceId: string, newSortOrder: number) => {
  const list = managedSources.value
  const oldIndex = list.findIndex(item => item.id === sourceId)
  if (oldIndex < 0) return
  const clamped = Math.max(1, Math.min(newSortOrder, list.length))
  const newIndex = clamped - 1
  if (newIndex === oldIndex) {
    return
  }
  const row = list.splice(oldIndex, 1)[0]
  list.splice(newIndex, 0, row)
  list.forEach((item, index) => {
    item.sortOrder = index + 1
  })
  axios.post('/api/subscription-sources/reorder', list.map(item => item.id)).then(() => {
    ElMessage.success('排序已更新')
  })
}

const loadManagedSources = () => {
  axios.get('/api/subscription-sources').then(({data}) => {
    managedSources.value = data
    nextTick(() => enablePluginRowDrop())
  })
}

const loadPluginFilters = () => {
  axios.get('/api/plugin-filters').then(({data}) => {
    pluginFilters.value = data.map((item: PluginFilter) => normalizePluginFilter(item))
    nextTick(() => enablePluginFilterRowDrop())
  })
}

const loadPluginSettings = () => {
  axios.get('/api/settings/plugin_run_mode').then(({data}) => {
    pluginSettingsForm.value.pluginRunMode = data?.value || 'java'
  })
  loadGitHubProxyNodes()
  loadGitHubProxyList()
}

const loadGitHubProxyNodes = () => {
  // 加载预设节点
  axios.get('/api/settings/github-proxy/nodes').then(({data}) => {
    const defaultNodes = data || []

    // 加载自定义节点
    axios.get('/api/settings/github-proxy/custom-nodes').then(({data: customUrls}) => {
      const customNodes = (customUrls || []).map((url: string) => {
        try {
          const urlObj = new URL(url)
          return {
            label: '自定义节点',
            url: url,
            host: urlObj.host
          }
        } catch (e) {
          console.error('Invalid custom URL:', url, e)
          return null
        }
      }).filter((node: any) => node !== null)

      // 合并节点列表：预设节点 + 自定义节点
      githubProxyNodes.value = [...defaultNodes, ...customNodes]
    }).catch(() => {
      // 如果加载自定义节点失败，只使用预设节点
      githubProxyNodes.value = defaultNodes
    })
  })
}

const loadGitHubProxyList = () => {
  axios.get('/api/settings/github-proxy/list').then(({data}) => {
    githubProxyList.value = data || []
  })
}

const showAddProxyDialog = () => {
  customProxyUrls.value = ''
  addProxyDialogVisible.value = true
}

// 规范化 URL
const normalizeProxyUrl = (url: string): string => {
  let normalized = url.trim()
  if (!normalized) return ''

  // 添加协议
  if (!normalized.startsWith('http://') && !normalized.startsWith('https://')) {
    normalized = 'https://' + normalized
  }

  // 添加末尾斜杠
  if (!normalized.endsWith('/')) {
    normalized += '/'
  }

  return normalized
}

// 添加自定义代理到测速列表
const addCustomProxiesToList = () => {
  const lines = customProxyUrls.value.split('\n')
  const newUrls: string[] = []
  const existingUrls = new Set(githubProxyNodes.value.map(node => node.url))

  for (const line of lines) {
    const normalized = normalizeProxyUrl(line)
    if (!normalized) continue

    // 检查是否已存在
    if (existingUrls.has(normalized)) {
      continue
    }

    // 检查是否重复
    if (newUrls.includes(normalized)) {
      continue
    }

    newUrls.push(normalized)
    existingUrls.add(normalized)
  }

  if (newUrls.length === 0) {
    ElMessage.warning('没有有效的代理地址或所有地址已存在')
    return
  }

  // 获取现有的自定义节点URL列表
  axios.get('/api/settings/github-proxy/custom-nodes').then(({data}) => {
    const existingCustomUrls = data || []
    const allCustomUrls = [...existingCustomUrls, ...newUrls]

    // 保存到 settings
    axios.post('/api/settings/github-proxy/custom-nodes', allCustomUrls)
      .then(() => {
        // 添加到 githubProxyNodes
        newUrls.forEach(url => {
          try {
            const urlObj = new URL(url)
            githubProxyNodes.value.push({
              label: '自定义节点',
              url: url,
              host: urlObj.host
            })
          } catch (e) {
            console.error('Invalid URL:', url, e)
          }
        })

        addProxyDialogVisible.value = false
        ElMessage.success(`已添加 ${newUrls.length} 个自定义节点并保存`)

        // 自动开始测速
        benchmarkCustomProxies(newUrls)
      })
      .catch(() => {
        ElMessage.error('保存自定义节点失败')
      })
  })
}

// 测速自定义代理
const benchmarkCustomProxies = (urls: string[]) => {
  if (benchmarking.value) {
    ElMessage.warning('正在测速中，请稍候')
    return
  }

  benchmarking.value = true

  // 为新添加的节点设置"测速中"状态
  urls.forEach(url => {
    benchmarkResults.value.set(url, { pending: true })
  })

  ElMessage.info(`开始测速 ${urls.length} 个自定义节点...`)

  // 启动异步测速
  axios.post('/api/settings/github-proxy/benchmark/start', { urls })
    .then(() => {
      // 轮询获取结果
      pollCustomProxiesResults(urls)
    })
    .catch(() => {
      ElMessage.error('启动测速失败')
      benchmarking.value = false
      urls.forEach(url => {
        benchmarkResults.value.delete(url)
      })
    })
}

// 轮询自定义节点测速结果
const pollCustomProxiesResults = (urls: string[]) => {
  const poll = () => {
    axios.get('/api/settings/github-proxy/benchmark/results')
      .then(({data}) => {
        // 更新结果
        const results = data.results || {}
        Object.keys(results).forEach((url: string) => {
          benchmarkResults.value.set(url, results[url])
        })

        // 检查是否还在运行
        if (data.isRunning) {
          setTimeout(poll, 500)
        } else {
          benchmarking.value = false

          // 统计成功的节点
          const successCount = urls.filter(url => {
            const result = benchmarkResults.value.get(url)
            return result && result.success
          }).length

          if (successCount > 0) {
            ElMessage.success(`测速完成！${successCount}/${urls.length} 个节点可用`)
          } else {
            ElMessage.warning('测速完成，但没有可用节点')
          }
        }
      })
      .catch(() => {
        benchmarking.value = false
        ElMessage.error('获取测速结果失败')
      })
  }

  poll()
}

const addProxyToList = () => {
  if (githubProxyList.value.length >= 5) {
    ElMessage.warning('最多只能配置 5 个代理节点')
    return
  }

  const url = newProxyUrl.value.trim()
  if (githubProxyList.value.includes(url)) {
    ElMessage.warning('该代理已存在')
    return
  }

  githubProxyList.value.push(url)
  addProxyDialogVisible.value = false
  ElMessage.success('已添加到列表，请点击"保存代理列表"生效')
}

const removeProxy = (index: number) => {
  githubProxyList.value.splice(index, 1)
  ElMessage.success('已从列表移除，请点击"保存代理列表"生效')
}

const moveProxyUp = (index: number) => {
  if (index === 0) return
  const temp = githubProxyList.value[index]
  githubProxyList.value[index] = githubProxyList.value[index - 1]
  githubProxyList.value[index - 1] = temp
}

const moveProxyDown = (index: number) => {
  if (index === githubProxyList.value.length - 1) return
  const temp = githubProxyList.value[index]
  githubProxyList.value[index] = githubProxyList.value[index + 1]
  githubProxyList.value[index + 1] = temp
}

const benchmarkAllProxies = () => {
  if (benchmarking.value) return
  if (githubProxyList.value.length === 0) {
    ElMessage.warning('请先添加代理节点')
    return
  }

  benchmarking.value = true
  benchmarkResults.value.clear()

  // 为所有待测节点设置"测速中"状态
  githubProxyList.value.forEach(url => {
    benchmarkResults.value.set(url, { pending: true })
  })

  // 启动异步测速任务
  axios.post('/api/settings/github-proxy/benchmark/start', { urls: githubProxyList.value })
    .then(() => {
      // 开始轮询获取结果
      pollBenchmarkResults()
    })
    .catch(() => {
      ElMessage.error('启动测速失败')
      benchmarking.value = false
      benchmarkResults.value.clear()
    })
}

const pollBenchmarkResults = () => {
  const poll = () => {
    axios.get('/api/settings/github-proxy/benchmark/results')
      .then(({data}) => {
        // 更新结果
        const results = data.results || {}
        Object.keys(results).forEach((url: string) => {
          benchmarkResults.value.set(url, results[url])
        })

        // 检查是否还在运行
        if (data.isRunning) {
          // 继续轮询（500ms 间隔）
          setTimeout(poll, 500)
        } else {
          // 测速完成
          benchmarking.value = false
          ElMessage.success('测速完成')
        }
      })
      .catch(() => {
        benchmarking.value = false
        ElMessage.error('获取测速结果失败')
      })
  }

  poll()
}

const saveGitHubProxyList = () => {
  axios.post('/api/settings/github-proxy/list', githubProxyList.value)
    .then(({data}) => {
      ElMessage.success(`已保存 ${data.count} 个代理节点到 /data/github_proxy.txt`)
    })
    .catch(() => {
      ElMessage.error('保存失败')
    })
}

const autoSelectFastest = () => {
  if (benchmarking.value) return

  benchmarking.value = true
  benchmarkResults.value.clear()
  selectedProxies.value = []

  // 获取所有预设节点
  const allUrls = githubProxyNodes.value.map(node => node.url)

  // 为所有待测节点设置"测速中"状态
  allUrls.forEach(url => {
    benchmarkResults.value.set(url, { pending: true })
  })

  // 打开对话框
  smartSelectDialogVisible.value = true
  ElMessage.info('开始测速所有预设节点...')

  // 启动异步测速
  axios.post('/api/settings/github-proxy/benchmark/start', { urls: allUrls })
    .then(() => {
      // 轮询获取结果并自动选择最快的 5 个
      pollAndSelectFastest()
    })
    .catch(() => {
      ElMessage.error('启动测速失败')
      benchmarking.value = false
      benchmarkResults.value.clear()
      smartSelectDialogVisible.value = false
    })
}

const pollAndSelectFastest = () => {
  const poll = () => {
    axios.get('/api/settings/github-proxy/benchmark/results')
      .then(({data}) => {
        // 更新结果
        const results = data.results || {}
        Object.keys(results).forEach((url: string) => {
          benchmarkResults.value.set(url, results[url])
        })

        // 实时选择最快的 5 个节点并更新到列表（仅用于预览）
        const successNodes = Array.from(benchmarkResults.value.entries())
          .filter(([url, result]) => result.success && result.latency != null)
          .sort((a, b) => a[1].latency - b[1].latency)
          .slice(0, 5)
          .map(([url]) => url)

        // 检查是否还在运行
        if (data.isRunning) {
          // 继续轮询
          setTimeout(poll, 500)
        } else {
          // 测速完成
          benchmarking.value = false
          if (successNodes.length === 0) {
            ElMessage.warning('没有测速成功的节点')
          } else {
            // 自动勾选最快的 5 个节点
            selectedProxies.value = successNodes

            // 使用 nextTick 确保表格已经渲染完成
            nextTick(() => {
              if (proxyTableRef.value) {
                // 先清空所有选择
                proxyTableRef.value.clearSelection()

                // 勾选最快的 5 个节点
                const nodesToSelect = githubProxyNodes.value.filter(node =>
                  successNodes.includes(node.url)
                )
                nodesToSelect.forEach(node => {
                  proxyTableRef.value.toggleRowSelection(node, true)
                })
              }
            })

            ElMessage.success(`已自动选择最快的 ${successNodes.length} 个节点，请确认后点击"确认选择"`)
          }
        }
      })
      .catch(() => {
        benchmarking.value = false
        ElMessage.error('获取测速结果失败')
      })
  }

  poll()
}

const selectFastestNodes = () => {
  // 过滤成功的节点并按延迟排序
  const successNodes = Array.from(benchmarkResults.value.entries())
    .filter(([url, result]) => result.success && result.latency != null)
    .sort((a, b) => a[1].latency - b[1].latency)
    .slice(0, 5)
    .map(([url]) => url)

  if (successNodes.length === 0) {
    ElMessage.warning('没有测速成功的节点')
    return
  }

  // 更新代理列表
  githubProxyList.value = successNodes
  ElMessage.success(`已自动选择最快的 ${successNodes.length} 个节点，请点击"保存代理列表"生效`)
}

const benchmarkGitHubProxy = () => {
  if (benchmarking.value) return

  benchmarking.value = true
  benchmarkResults.value.clear()

  const urls = githubProxyNodes.value.map(node => node.url)

  axios.post('/api/settings/github-proxy/benchmark', { urls })
    .then(({data}) => {
      data.forEach((result: any) => {
        benchmarkResults.value.set(result.url, result)
      })
      ElMessage.success('测速完成')
    })
    .catch(() => {
      ElMessage.error('测速失败')
    })
    .finally(() => {
      benchmarking.value = false
    })
}

const formatNodeLabel = (node: any) => {
  if (!node.url) {
    return '无代理（直连）'
  }
  return `${node.label} (${node.host || node.url})`
}

const formatBenchmarkResult = (result: any) => {
  if (!result) return ''
  if (isBenchmarkPending(result)) {
    return '测速中...'
  }
  // 如果有明确的 success 字段，说明测速已完成，优先显示结果
  if (result.success !== undefined && result.success !== null) {
    if (!result.success) {
      return '失败'
    }
    return `${result.latency}ms`
  }
  return ''
}

// 处理代理节点选择变化
const handleProxySelectionChange = (selection: any[]) => {
  if (selection.length > 5) {
    ElMessage.warning('最多只能选择 5 个节点')
    // 只保留前5个选择
    nextTick(() => {
      if (proxyTableRef.value) {
        proxyTableRef.value.clearSelection()
        selection.slice(0, 5).forEach((node: any) => {
          proxyTableRef.value.toggleRowSelection(node, true)
        })
      }
    })
    return
  }
  selectedProxies.value = selection.map((node: any) => node.url)
}

// 确认选择代理节点
const confirmProxySelection = () => {
  if (selectedProxies.value.length === 0) {
    ElMessage.warning('请至少选择一个节点')
    return
  }

  githubProxyList.value = selectedProxies.value
  smartSelectDialogVisible.value = false
  ElMessage.success(`已选择 ${selectedProxies.value.length} 个节点，请点击"保存代理列表"生效`)
}

// 获取节点延迟排名
const getNodeRank = (url: string) => {
  const result = benchmarkResults.value.get(url)
  if (!result || !result.success || result.latency == null) {
    return 0
  }

  // 获取所有成功的节点并按延迟排序
  const successNodes = Array.from(benchmarkResults.value.entries())
    .filter(([_, r]) => r.success && r.latency != null)
    .sort((a, b) => a[1].latency - b[1].latency)

  // 找到当前节点的排名
  const rank = successNodes.findIndex(([u]) => u === url)
  return rank >= 0 ? rank + 1 : 0
}

const showGlobalConfig = () => {
  openEditor(true)
}

const loadGlobalConfig = () => {
  axios.get('/api/subscriptions/global-config').then(response => {
    globalConfigJson.value = JSON.stringify(response.data || {})
  })
}

const openEditor = (isGlobal: boolean) => {
  editorTargetIsGlobal.value = isGlobal
  if (isGlobal) {
    globalReferenceSid.value = subscriptions.value.length ? (subscriptions.value[0] as any).sid : ''
    loadGlobalConfig()
  }
  editorVisible.value = true
}

const openRawJsonEditor = () => {
  rawJsonEditorText.value = form.value.override || ''
  rawJsonEditorVisible.value = true
}

const saveRawJson = () => {
  const text = rawJsonEditorText.value.trim()
  if (!text) {
    form.value.override = ''
    rawJsonEditorVisible.value = false
    ElMessage.success('已清空定制配置')
    return
  }
  try {
    JSON.parse(text)
    form.value.override = text
    rawJsonEditorVisible.value = false
    ElMessage.success('保存成功')
  } catch (e) {
    ElMessage.error('JSON 格式错误: ' + (e as Error).message)
  }
}

const saveEditor = () => {
  const value = editorRef.value?.getValue()
  if (value === null || value === undefined) {
    ElMessage.error('JSON 格式错误,请修正后再保存')
    return
  }
  jsonPreviewText.value = value ? JSON.stringify(JSON.parse(value), null, 2) : '(空)'
  jsonPreviewVisible.value = true
}

const confirmSaveEditor = () => {
  const value = editorRef.value?.getValue()
  if (value === null || value === undefined) return
  jsonPreviewVisible.value = false
  if (editorTargetIsGlobal.value) {
    let config: any = {}
    try { config = value ? JSON.parse(value) : {} } catch { ElMessage.error('JSON格式错误'); return }
    axios.put('/api/subscriptions/global-config', config).then(() => {
      ElMessage.success('全局配置保存成功')
      editorVisible.value = false
    })
  } else {
    form.value.override = value
    axios.post('/api/subscriptions', form.value).then(() => {
      editorVisible.value = false
      ElMessage.success('订阅配置保存成功')
      load()
    })
  }
}

const showPlugins = () => {
  pluginVisible.value = true
  resetPluginForm()
  pluginImportForm.value.url = localStorage.getItem(PLUGIN_REPO_URL_KEY) || ''
  selectedPluginIds.value = []
  loadManagedSources()
  loadPluginSettings()
}

const openPluginCompiler = () => {
  resetPluginCompilerForm()
  pluginCompilerVisible.value = true
  loadSecspiderKeyStatus()
}

const loadSecspiderKeyStatus = async () => {
  secspiderKeyLoading.value = true
  try {
    const {data} = await axios.get('/api/plugins/secspider/key')
    secspiderKeyStatus.value = data
  } finally {
    secspiderKeyLoading.value = false
  }
}

const generateSecspiderKey = async () => {
  secspiderKeyLoading.value = true
  try {
    const {data} = await axios.post('/api/plugins/secspider/key/generate')
    secspiderKeyStatus.value = data
    ElMessage.success('密钥对已就绪')
  } finally {
    secspiderKeyLoading.value = false
  }
}

const resetSecspiderKey = async () => {
  secspiderKeyLoading.value = true
  try {
    const {data} = await axios.post('/api/plugins/secspider/key/reset')
    secspiderKeyStatus.value = data
    ElMessage.warning('密钥对已重置，旧自有插件需要重新编译')
  } finally {
    secspiderKeyLoading.value = false
  }
}

const showPluginFilters = () => {
  pluginFilterVisible.value = true
  resetPluginFilterForm()
  selectedPluginFilterIds.value = []
  loadPlugins()
  loadPluginFilters()
}

const addPlugin = () => {
  axios.post('/api/plugins', {
    url: pluginForm.value.url,
    name: pluginForm.value.name
  }).then(() => {
    ElMessage.success('添加成功')
    resetPluginForm()
    loadManagedSources()
  })
}

const addPluginFilter = () => {
  if (!validatePluginFilter(pluginFilterForm.value)) {
    return
  }
  axios.post('/api/plugin-filters', pluginFilterPayload(pluginFilterForm.value)).then(() => {
    ElMessage.success('添加成功')
    resetPluginFilterForm()
    loadPluginFilters()
  })
}

const importPlugins = async () => {
  const url = pluginImportForm.value.url.trim()
  if (!url) {
    return
  }
  importingPlugins.value = true
  try {
    const {data} = await axios.post('/api/plugins/import', {
      url
    })
    const action = data.failedCount > 0 ? ElMessage.warning : ElMessage.success
    action(`导入完成，新增 ${data.createdCount}，刷新 ${data.refreshedCount}，跳过 ${data.skippedCount}，失败 ${data.failedCount}`)
    loadManagedSources()
  } finally {
    importingPlugins.value = false
  }
}

const compilePlugin = async () => {
  const form = pluginCompilerForm.value
  if (!form.name.trim()) {
    ElMessage.warning('请输入插件名称')
    return
  }
  if (!form.source.trim()) {
    ElMessage.warning('请粘贴插件明文')
    return
  }
  if (!form.useManagedKey && !form.privateKey.trim()) {
    ElMessage.warning('请粘贴 Ed25519 私钥')
    return
  }
  if (!form.useManagedKey && !form.masterSecret.trim()) {
    ElMessage.warning('请填写 master secret')
    return
  }

  pluginCompiling.value = true
  try {
    const {data} = await axios.post('/api/plugins/compile/secspider', {
      name: form.name.trim(),
      version: form.version,
      remark: form.remark,
      id: form.id.trim(),
      kid: form.kid.trim(),
      source: form.source,
      privateKey: form.privateKey,
      publicKey: form.publicKey,
      masterSecret: form.masterSecret,
      useManagedKey: form.useManagedKey,
      autoImport: form.autoImport
    })
    pluginCompilerResult.value = data
    pluginCompilerResultTab.value = 'package'
    ElMessage.success(data.importedPluginId ? '编译完成，已自动导入插件管理' : '编译完成')
    if (data.importedPluginId) {
      loadManagedSources()
    }
  } finally {
    pluginCompiling.value = false
  }
}

const savePluginSettings = () => {
  axios.post('/api/settings', {
    name: 'plugin_run_mode',
    value: pluginSettingsForm.value.pluginRunMode
  }).then(() => {
    ElMessage.success('插件运行模式已保存')
  })
}

const isSourceDeletable = (row: ManagedSource) => {
  return row.deletable
}

const onPluginSelectionChange = (rows: ManagedSource[]) => {
  selectedPluginIds.value = rows
    .filter(item => item.pluginId != null)
    .map(item => item.pluginId as number)
}

const deleteSelectedPlugins = () => {
  axios.post('/api/plugins/delete-batch', {
    ids: selectedPluginIds.value
  }).then(({data}) => {
    ElMessage.success(`已删除 ${data} 个插件`)
    selectedPluginIds.value = []
    loadManagedSources()
  })
}

const onPluginFilterSelectionChange = (rows: PluginFilter[]) => {
  selectedPluginFilterIds.value = rows.map(item => item.id)
}

const deleteSelectedPluginFilters = () => {
  axios.post('/api/plugin-filters/delete-batch', {
    ids: selectedPluginFilterIds.value
  }).then(({data}) => {
    ElMessage.success(`已删除 ${data} 个过滤器`)
    selectedPluginFilterIds.value = []
    loadPluginFilters()
  })
}

const updateSource = (source: ManagedSource) => {
  axios.put('/api/subscription-sources/' + source.id, {
    name: source.name,
    enabled: source.enabled,
    extend: source.extend
  }).then(({data}) => {
    Object.assign(source, data)
    ElMessage.success('更新成功')
  })
}

const openSourceExtendDialog = (source: ManagedSource) => {
  sourceExtendTarget.value = source
  sourceExtendText.value = source.extend || ''
  sourceExtendVisible.value = true
}

const saveSourceExtend = () => {
  if (!sourceExtendTarget.value) {
    return
  }
  sourceExtendTarget.value.extend = sourceExtendText.value
  updateSource(sourceExtendTarget.value)
  sourceExtendVisible.value = false
}

const updatePluginFilter = (filter: PluginFilter) => {
  if (!validatePluginFilter(filter)) {
    return
  }
  axios.put('/api/plugin-filters/' + filter.id, pluginFilterPayload(filter)).then(({data}) => {
    Object.assign(filter, normalizePluginFilter(data))
    if (pluginFilterConfigTarget.value?.id === filter.id) {
      pluginFilterConfigTarget.value = filter
    }
    ElMessage.success('更新成功')
  })
}

const refreshPlugin = (id: number | null) => {
  if (id == null) {
    return
  }
  axios.post('/api/plugins/' + id + '/refresh').then(() => {
    loadManagedSources()
    ElMessage.success('刷新完成')
  })
}

const refreshPluginFilter = (id: number) => {
  axios.post('/api/plugin-filters/' + id + '/refresh').then(({data}) => {
    const index = pluginFilters.value.findIndex(item => item.id === id)
    if (index >= 0) {
      pluginFilters.value[index] = normalizePluginFilter(data)
    }
    ElMessage.success('刷新完成')
  })
}

const deletePlugin = (id: number | null) => {
  if (id == null) {
    return
  }
  axios.delete('/api/plugins/' + id).then(() => {
    ElMessage.success('删除成功')
    loadManagedSources()
  })
}

const deletePluginFilter = (id: number) => {
  axios.delete('/api/plugin-filters/' + id).then(() => {
    ElMessage.success('删除成功')
    loadPluginFilters()
  })
}

const handleEdit = (data: any) => {
  dialogTitle.value = '更新订阅 - ' + data.name
  updateAction.value = true
  form.value = {
    id: data.id,
    sid: data.sid,
    name: data.name,
    url: data.url,
    sort: data.sort,
    override: data.override
  }

  formVisible.value = true
}

const showDetails = (data: any) => {
  form.value = data
  dialogTitle.value = '订阅数据 - ' + data.name
  axios.get('/sub' + token.value + '/' + data.sid).then(({data}) => {
    jsonData.value = data
    detailVisible.value = true
  })
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteSub = () => {
  dialogVisible.value = false
  axios.delete('/api/subscriptions/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const loadDevices = () => {
  console.log('📱 开始加载设备列表')
  loadingDevices.value = true

  axios.get('/api/devices').then(({data}) => {
    console.log('✅ 设备加载成功:', data.length, '个')
    devices.value = data
  }).catch((error) => {
    console.error('❌ 设备加载失败:', error)
  }).finally(() => {
    loadingDevices.value = false
  })
}

const showPush = () => {
  pushForm.value.id = devices.value[0].id
  pushForm.value.sid = subscriptions.value[0].sid
  pushForm.value.name = subscriptions.value[0].name
  pushForm.value.token = tokens.value[0]
  pushForm.value.url = currentUrl + '/sub/' + pushForm.value.token + '/' + pushForm.value.sid
  push.value = true
}

const onTokenChange = () => {
  pushForm.value.url = currentUrl + '/sub/' + pushForm.value.token + '/' + pushForm.value.sid
}

const pushConfig = () => {
  axios.post(`/api/devices/${pushForm.value.id}/push?type=setting&name=${pushForm.value.name}&url=${pushForm.value.url}`).then(() => {
    ElMessage.success('推送成功')
  })
}

const showScan = () => {
  axios.get('/api/qr-code').then(({data}) => {
    base64QrCode.value = data
    scanVisible.value = true
  })
}

const syncHistory = (id: number) => {
  axios.post(`/api/devices/${id}/sync?mode=0`).then(() => {
    ElMessage.success('同步成功')
  })
}

const scanDevices = () => {
  axios.post(`/api/devices/-/scan`).then(({data}) => {
    ElMessage.success(`扫描完成，添加了${data}个设备`)
    loadDevices()
  })
}

const showDelete = (data: Device) => {
  device.value = data
  confirm.value = true
}

const addDevice = () => {
  if (!device.value.ip) {
    return
  }
  axios.post(`/api/devices?ip=` + device.value.ip).then(() => {
    confirm.value = false
    device.value.ip = ''
    ElMessage.success('添加成功')
    loadDevices()
  })
}

const deleteDevice = () => {
  axios.delete(`/api/devices/${device.value.id}`).then(() => {
    confirm.value = false
    ElMessage.success('删除成功')
    loadDevices()
  })
}

const setAuthType = () => {
  base64QrCode.value = ''
  axios.post('/api/settings', {name: 'tg_auth_type', value: tgAuthType.value})
}

const setScanned = () => {
  axios.post('/api/settings', {name: 'tg_scanned', value: 'true'}).then(() => {
    base64QrCode.value = ''
  })
}

const sendTgPhone = () => {
  axios.post('/api/settings', {name: 'tg_phone', value: tgPhone.value})
}

const sendTgCode = () => {
  axios.post('/api/settings', {name: 'tg_code', value: tgCode.value})
}

const sendTgPassword = () => {
  axios.post('/api/settings', {name: 'tg_password', value: tgPassword.value})
}

const handleConfirm = () => {
  axios.post('/api/subscriptions', form.value).then(() => {
    formVisible.value = false
    load()
  })
}

const syncCat = () => {
  axios.post('/api/cat/sync').then(({data}) => {
    if (data) {
      ElMessage.warning('同步失败')
    } else {
      ElMessage.success('同步任务开始执行')
      setTimeout(loadVersion, 1000)
    }
  })
}

const load = () => {
  console.log('📋 开始加载订阅列表')
  console.time('load-subscriptions')
  loading.value = true

  axios.get('/api/subscriptions').then(({data}) => {
    console.log('✅ 订阅加载成功:', data.length, '条')

    // 检查数据大小
    const dataSize = JSON.stringify(data).length
    console.log('📊 数据大小:', (dataSize / 1024).toFixed(2), 'KB')

    if (dataSize > 1024 * 1024) {
      console.error('⚠️ 数据过大！超过 1MB')
      ElMessage.warning('订阅数据较大，加载可能较慢')
    }

    subscriptions.value = data
    console.timeEnd('load-subscriptions')
  }).catch((error) => {
    console.error('❌ 订阅加载失败:', error)
  }).finally(() => {
    loading.value = false
  })
}

const loadVersion = () => {
  axios.get("/pg/version").then(({data}) => {
    pgLocal.value = data.local
    pgRemote.value = data.remote
  })
  axios.get("/zx/version").then(({data}) => {
    zxLocal.value = data.local
    zxRemote.value = data.remote
    zxLocal2.value = data.local2
    zxRemote2.value = data.remote2
  })
  axios.get("/xs/version").then(({data}) => {
    xsLocal.value = data.local
    xsRemote.value = data.remote
  })
}

watch(() => pluginImportForm.value.url, (value) => {
  localStorage.setItem(PLUGIN_REPO_URL_KEY, value)
})

onMounted(() => {
  axios.get('/api/token').then(({data}) => {
    tokens.value =  data.token ? data.token.split(",") : ['-']
    enabledToken.value = data.enabledToken
    selectedToken.value = data.token ? data.token.split(",")[0] : ""
    load()
    loadVersion()
    axios.get('/api/settings/tg_phase').then(({data}) => {
      tgPhase.value = data.value
    })
  })
  loadDevices()
  axios.get('/api/basic-auth-credentials').then(({data}) => {
    basicAuthUser.value = data.username
    basicAuthPass.value = data.password
  }).catch(() => {})
})

onUnmounted(() => {
  clearInterval(timer)
  pluginSortable?.destroy()
  pluginFilterSortable?.destroy()
})
</script>

<style scoped>
.space {
  margin-bottom: 6px;
}

.hint {
  margin-left: 16px;
}

.pointer {
  cursor: move;
}

.plugin-filter-scope {
  display: flex;
  gap: 8px;
}

.filter-config-dialog {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.filter-config-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.filter-config-title {
  font-size: 16px;
  font-weight: 600;
  word-break: break-all;
}

.filter-config-subtitle {
  color: var(--el-text-color-secondary);
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 13px;
}

.filter-config-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.filter-config-extra {
  border-top: 1px solid var(--el-border-color-lighter);
  margin-top: 16px;
  padding-top: 16px;
}

.filter-config-extra-header {
  align-items: center;
  display: flex;
  justify-content: space-between;
  margin-bottom: 10px;
}

.filter-config-extra-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.filter-config-extra-row {
  display: grid;
  gap: 10px;
  grid-template-columns: 180px 1fr 60px;
}

.filter-config-extra-empty {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.plugin-compiler-guide {
  margin-bottom: 14px;
}

.plugin-compiler-key-actions {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.plugin-compiler-key-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.plugin-compiler-guide-body {
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.7;
}

.plugin-compiler-guide-body ol {
  margin: 0 0 12px 20px;
  padding: 0;
}

.plugin-compiler-example-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.plugin-compiler-example-title {
  color: var(--el-text-color-primary);
  font-weight: 600;
  margin-bottom: 6px;
}

.plugin-compiler-example {
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  margin: 0;
  overflow: auto;
  padding: 10px;
  white-space: pre-wrap;
}

@media (max-width: 900px) {
  .plugin-compiler-example-grid {
    grid-template-columns: 1fr;
  }
}

.json pre {
  height: 600px;
  overflow: scroll;
}
</style>
