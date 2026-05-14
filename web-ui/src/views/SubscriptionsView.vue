<template>
  <div class="subscriptions">
    <h1>订阅列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button @click="showPlugins">插件管理</el-button>
      <el-button @click="showPluginFilters">过滤器管理</el-button>
      <el-button @click="showScan">同步影视</el-button>
      <el-button @click="showPush" v-if="devices.length">推送配置</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="subscriptions" border style="width: 100%">
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

    <el-row>
      猫影视配置接口：
      <a
        :href="currentUrl.replace('http://', 'http://alist:alist@').replace('https://', 'https://alist:alist@')+'/open'+token"
        target="_blank">
        {{
          currentUrl.replace('http://', 'http://alist:alist@').replace('https://', 'https://alist:alist@')
        }}/open{{ token }}
      </a>
    </el-row>
    <el-row>
      猫影视node配置接口：
      <a
        :href="currentUrl.replace('http://', 'http://alist:alist@').replace('https://', 'https://alist:alist@')+'/node'+(token ? token : '/-')+'/index.config.js'"
        target="_blank">
        {{
          currentUrl.replace('http://', 'http://alist:alist@').replace('https://', 'https://alist:alist@')
        }}/node{{ token ? token : '/-' }}/index.js.md5
      </a>
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
      <el-button @click="syncCat">同步文件</el-button>
    </el-row>

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
        <el-form-item label="排序字段" label-width="140">
          <el-input v-model="form.sort" autocomplete="off" placeholder="留空保持默认排序"/>
        </el-form-item>
        <el-form-item label="定制" label-width="140">
          <el-input v-model="form.override" type="textarea" rows="15"/>
          <a href="https://www.json.cn/" target="_blank">JSON验证</a>
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
      <el-scrollbar height="800px">
        <json-viewer :value="jsonData" expanded copyable show-double-quotes :show-array-index="false"
                     :expand-depth=5></json-viewer>
      </el-scrollbar>
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

      <el-table :data="devices" border style="width: 100%">
        <el-table-column prop="name" label="名称" sortable width="180"/>
        <el-table-column prop="uuid" label="ID" sortable width="180"/>
        <el-table-column prop="ip" label="URL地址" sortable>
          <template #default="scope">
            <a :href="scope.row.ip" target="_blank">{{ scope.row.ip }}</a>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="100">
          <template #default="scope">
            <el-button link type="primary" size="small" @click="syncHistory(scope.row.id)">同步</el-button>
            <el-button link type="danger" size="small" @click="showDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
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

    <el-dialog v-model="pluginVisible" title="插件管理" fullscreen>
      <el-form :inline="true" :model="pluginForm">
        <el-form-item label="插件地址" required>
          <el-input v-model="pluginForm.url" style="width: 460px" placeholder="https://example.com/plugin.txt"/>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="pluginForm.name" style="width: 180px" placeholder="留空用文件名"/>
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
        </el-form-item>
      </el-form>
      <el-progress v-if="importingPlugins" :percentage="100" :indeterminate="true" :duration="5"/>

      <el-form :inline="true" :model="pluginSettingsForm">
        <el-form-item label="GitHub代理">
          <el-input
            v-model="pluginSettingsForm.githubProxy"
            style="width: 460px"
            placeholder="https://gh.llkk.cc/"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="saveGithubProxy">保存代理</el-button>
        </el-form-item>
        <el-form-item>
          <el-button type="danger" :disabled="selectedPluginIds.length === 0" @click="deleteSelectedPlugins">
            批量删除
          </el-button>
        </el-form-item>
      </el-form>

      <el-table :data="plugins" row-key="id" id="plugins-table" border style="width: 100%" @selection-change="onPluginSelectionChange">
        <el-table-column type="selection" width="55"/>
        <el-table-column label="顺序" width="80">
          <template #default="scope">
            <span class="pointer">{{ scope.row.sortOrder }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" width="180">
          <template #default="scope">
            <el-input v-model="scope.row.name" @change="updatePlugin(scope.row)"/>
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
            <el-switch v-model="scope.row.enabled" @change="updatePlugin(scope.row)"/>
          </template>
        </el-table-column>
        <el-table-column prop="extend" label="扩展配置" width="220">
          <template #default="scope">
            <el-input v-model="scope.row.extend" @change="updatePlugin(scope.row)"/>
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
            <el-button link type="primary" @click="refreshPlugin(scope.row.id)">刷新</el-button>
            <el-button link type="danger" @click="deletePlugin(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
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

      <el-table
        :data="pluginFilters"
        row-key="id"
        id="plugin-filters-table"
        border
        style="width: 100%"
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

  </div>
</template>

<script setup lang="ts">
import {nextTick, onMounted, onUnmounted, ref, watch} from 'vue'
import axios from "axios"
import {ElMessage} from "element-plus";
import Sortable from "sortablejs";
import type {Device} from "@/model/Device";
import PluginFilterConfigFieldEditor from "@/components/PluginFilterConfigFieldEditor.vue";

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
const tgPhase = ref(0)
const tgPhone = ref('')
const tgCode = ref('')
const tgPassword = ref('')
const tgAuthType = ref('qr')
const base64QrCode = ref('')
const token = ref('')
const pgLocal = ref('')
const pgRemote = ref('')
const zxLocal = ref('')
const zxRemote = ref('')
const zxLocal2 = ref('')
const zxRemote2 = ref('')
const updateAction = ref(false)
const dialogTitle = ref('')
const jsonData = ref({})
const subscriptions = ref<Sub[]>([])
const tokens = ref([])
const devices = ref<Device[]>([])
const detailVisible = ref(false)
const formVisible = ref(false)
const dialogVisible = ref(false)
const pluginVisible = ref(false)
const pluginFilterVisible = ref(false)
const pluginFilterConfigVisible = ref(false)
const importingPlugins = ref(false)
const tgVisible = ref(false)
const scanVisible = ref(false)
const confirm = ref(false)
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
const plugins = ref<Plugin[]>([])
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
const pluginSettingsForm = ref({
  githubProxy: ''
})
const selectedPluginIds = ref<number[]>([])
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
      const row = plugins.value.splice(oldIndex, 1)[0]
      plugins.value.splice(newIndex, 0, row)
      plugins.value.forEach((item, index) => {
        item.sortOrder = index + 1
      })
      axios.post('/api/plugins/reorder', plugins.value.map(item => item.id)).then(() => {
        ElMessage.success('排序已更新')
        loadPlugins()
      })
    }
  })
}

const enablePluginFilterRowDrop = () => {
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
  axios.get('/api/settings/github_proxy').then(({data}) => {
    pluginSettingsForm.value.githubProxy = data?.value || ''
  })
}

const showPlugins = () => {
  pluginVisible.value = true
  resetPluginForm()
  pluginImportForm.value.url = localStorage.getItem(PLUGIN_REPO_URL_KEY) || ''
  selectedPluginIds.value = []
  loadPlugins()
  loadPluginSettings()
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
    loadPlugins()
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
    loadPlugins()
  } finally {
    importingPlugins.value = false
  }
}

const saveGithubProxy = () => {
  axios.post('/api/settings', {
    name: 'github_proxy',
    value: pluginSettingsForm.value.githubProxy
  }).then(() => {
    ElMessage.success('GitHub 代理已保存')
  })
}

const onPluginSelectionChange = (rows: Plugin[]) => {
  selectedPluginIds.value = rows.map(item => item.id)
}

const deleteSelectedPlugins = () => {
  axios.post('/api/plugins/delete-batch', {
    ids: selectedPluginIds.value
  }).then(({data}) => {
    ElMessage.success(`已删除 ${data} 个插件`)
    selectedPluginIds.value = []
    loadPlugins()
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

const updatePlugin = (plugin: Plugin) => {
  axios.put('/api/plugins/' + plugin.id, plugin).then(({data}) => {
    Object.assign(plugin, data)
    ElMessage.success('更新成功')
  })
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

const refreshPlugin = (id: number) => {
  axios.post('/api/plugins/' + id + '/refresh').then(({data}) => {
    const index = plugins.value.findIndex(item => item.id === id)
    if (index >= 0) {
      plugins.value[index] = data
    }
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

const deletePlugin = (id: number) => {
  axios.delete('/api/plugins/' + id).then(() => {
    ElMessage.success('删除成功')
    loadPlugins()
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
  axios.get('/api/devices').then(({data}) => {
    devices.value = data
  })
}

const showPush = () => {
  pushForm.value.id = devices.value[0].id
  pushForm.value.sid = subscriptions.value[0].sid
  pushForm.value.token = tokens.value[0]
  pushForm.value.url = currentUrl + '/sub/' + pushForm.value.token + '/' + pushForm.value.sid
  push.value = true
}

const onTokenChange = () => {
  pushForm.value.url = currentUrl + '/sub/' + pushForm.value.token + '/' + pushForm.value.sid
}

const pushConfig = () => {
  axios.post(`/api/devices/${pushForm.value.id}/push?type=setting&url=${pushForm.value.url}`).then(() => {
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
  axios.get('/api/subscriptions').then(({data}) => {
    subscriptions.value = data
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
}

watch(() => pluginImportForm.value.url, (value) => {
  localStorage.setItem(PLUGIN_REPO_URL_KEY, value)
})

onMounted(() => {
  axios.get('/api/token').then(({data}) => {
    tokens.value =  data.token ? data.token.split(",") : ['-']
    token.value = data.enabledToken ? "/" + data.token.split(",")[0] : ""
    load()
    loadVersion()
    axios.get('/api/settings/tg_phase').then(({data}) => {
      tgPhase.value = data.value
    })
  })
  loadDevices()
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

.json pre {
  height: 600px;
  overflow: scroll;
}
</style>
