<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">资源列表</h1>
      <div class="page-actions">
        <el-input style="width: 200px;" v-model="keyword" @keyup="search">
          <template #append>
            <el-button :icon="Search" @click="search" />
          </template>
        </el-input>
        <el-select style="width: 90px" v-model="type" @change="filter">
          <el-option v-for="item in options" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button type="success" @click="showUpload">导入</el-button>
        <el-button type="success" @click="exportVisible = true">导出</el-button>
        <el-popconfirm @confirm="deleteShares" title="是否清空全部资源？">
          <template #reference>
            <el-button type="danger">清空</el-button>
          </template>
    </el-popconfirm>
    <el-button @click="refreshShares">刷新</el-button>
    <el-button type="primary" @click="handleAdd">添加</el-button>
    <el-button type="danger" @click="handleDeleteBatch" v-if="multipleSelection.length">删除</el-button>
      </div>
    </div>

    <div class="page-card">
    <div class="table-scroll-wrapper">
  <el-table :data="shares" v-loading="loading" border @selection-change="handleSelection" @sort-change="handleSort" style="width: 100%; min-width: 1200px">
    <el-table-column type="selection" width="55" />
    <el-table-column prop="id" label="ID" width="70" sortable="custom" />
    <el-table-column prop="path" label="路径" sortable="custom">
      <template #default="scope">
        <router-link :to="'/vod' + scope.row.path">
          {{ scope.row.path }}
        </router-link>
      </template>
    </el-table-column>
    <el-table-column prop="url" label="分享链接">
      <template #default="scope">
        <a v-if="getShareLink(scope.row)" :href="getShareLink(scope.row)" target="_blank">
          {{ getShareLink(scope.row) }}
        </a>
      </template>
    </el-table-column>
    <el-table-column prop="password" label="密码" width="120" />
    <el-table-column prop="type" label="类型" width="120" sortable="custom">
      <template #default="scope">
        {{ getShareTypeLabel(scope.row.type) }}
      </template>
    </el-table-column>
    <el-table-column prop="time" label="创建时间" width="180" sortable="custom">
      <template #default="scope">
        {{ new Date(scope.row.time).toLocaleString() }}
      </template>
    </el-table-column>
    <el-table-column fixed="right" label="操作" width="135">
      <template #default="scope">
        <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
        <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
  </div>
  <div>
    <el-pagination layout="total, prev, pager, next, jumper, sizes" :current-page="page" :page-size="size"
      :total="total" @current-change="loadShares" @size-change="handleSizeChange" />
  </div>
    </div>

    <div class="page-header" style="margin-top: 24px;">
      <h1 class="page-title">失败资源</h1>
      <div class="page-actions">
        <el-popconfirm @confirm="cleanStorages" title="是否删除全部失效资源？">
          <template #reference>
            <el-button type="danger">清理</el-button>
          </template>
        </el-popconfirm>
        <el-popconfirm @confirm="validateStorages" title="是否校验全部资源？">
          <template #reference>
            <el-button>校验</el-button>
          </template>
        </el-popconfirm>
        <el-button @click="refreshStorages">刷新</el-button>
        <el-button type="danger" @click="dialogVisible1 = true" v-if="selectedStorages.length">删除</el-button>
      </div>
    </div>

    <div class="page-card">
    <div class="table-scroll-wrapper">
  <el-table :data="storages" v-loading="loadingStorages" border @selection-change="handleSelectionStorages" style="width: 100%">
    <el-table-column type="selection" width="55" />
    <el-table-column prop="id" label="ID" width="70" />
    <el-table-column prop="mount_path" label="路径" />
    <el-table-column prop="status" label="状态" width="300">
      <template #default="scope">
        <div v-html="scope.row.status"></div>
      </template>
    </el-table-column>
    <el-table-column prop="driver" label="类型" width="120">
      <template #default="scope">
        <span v-if="scope.row.driver == 'AliyunShare'">阿里分享</span>
        <span v-else-if="scope.row.driver == 'PikPakShare'">PikPak分享</span>
        <span v-else-if="scope.row.driver == 'QuarkShare'">夸克分享</span>
        <span v-else-if="scope.row.driver == 'UCShare'">UC分享</span>
        <span v-else-if="scope.row.driver == '115 Share'">115分享</span>
        <span v-else-if="scope.row.driver == '189Share'">天翼分享</span>
        <span v-else-if="scope.row.driver == 'Yun139Share'">移动分享</span>
        <span v-else-if="scope.row.driver == 'ThunderShare'">迅雷分享</span>
        <span v-else-if="scope.row.driver == '123PanShare'">123分享</span>
        <span v-else-if="scope.row.driver == 'BaiduShare'">百度分享</span>
        <span v-else-if="scope.row.driver == 'GuangYaPanShare'">光鸭分享</span>
        <span v-else-if="scope.row.driver == 'Local'">本地存储</span>
        <span v-else-if="scope.row.driver == 'Alias'">别名</span>
        <span v-else>{{ scope.row.driver }}</span>
      </template>
    </el-table-column>
    <el-table-column fixed="right" label="操作" width="175">
      <template #default="scope">
        <el-button link type="primary" size="small" @click="reloadStorage(scope.row.id)">重新加载</el-button>
        <el-button link type="danger" size="small" @click="handleDeleteStorage(scope.row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
  </div>
  <div>
    <el-pagination layout="total, prev, pager, next, jumper, sizes" :current-page="page1" :total="total1"
      :page-size="size1" @current-change="loadStorages" @size-change="handleSize1Change" />
  </div>
    </div>
  </div>

  <el-dialog v-model="formVisible" width="65%" :title="dialogTitle">
    <el-form :model="form">
      <el-form-item label="挂载路径" label-width="140" required>
        <el-input v-model="form.path" autocomplete="off" />
      </el-form-item>
      <el-form-item v-if="form.type != 4 && form.type != 11" label="分享ID" label-width="140" required>
        <el-input v-model="form.shareId" autocomplete="off" placeholder="分享ID或者分享链接" />
      </el-form-item>
      <el-form-item v-if="form.type != 4 && form.type != 11" label="提取码" label-width="140">
        <el-input v-model="form.password" autocomplete="off" />
      </el-form-item>
      <el-form-item v-if="form.type == 4" label="本地路径" label-width="140">
        <el-input v-model="form.folderId" autocomplete="off" />
      </el-form-item>
      <el-form-item v-if="form.type != 4 && form.type != 11" label="文件夹ID" label-width="140">
        <el-input v-model="form.folderId" autocomplete="off" placeholder="默认为根目录或者从分享链接读取" />
      </el-form-item>

      <!-- STRM 存储特有配置 -->
      <template v-if="form.type == 11 && form.strmConfig">
        <el-form-item label="源路径" label-width="140" required>
          <el-input v-model="form.strmConfig.paths" type="textarea" :rows="3"
            placeholder="STRM文件指向的实际媒体文件所在路径，例如：/115/电影" autocomplete="off" />
        </el-form-item>
        <el-form-item label="站点URL" label-width="140" required>
          <el-input v-model="form.strmConfig.siteUrl" autocomplete="off"
            placeholder="AList站点访问地址，例如：http://localhost:5244" />
        </el-form-item>
        <el-form-item label="路径前缀" label-width="140">
          <el-input v-model="form.strmConfig.pathPrefix" autocomplete="off" placeholder="默认为 /d" />
        </el-form-item>
        <el-form-item label="下载文件类型" label-width="140">
          <el-input v-model="form.strmConfig.downloadFileTypes" autocomplete="off"
            placeholder="逗号分隔的文件扩展名，例如：ass,srt,vtt,sub,strm" />
        </el-form-item>
        <el-form-item label="过滤文件类型" label-width="140">
          <el-input v-model="form.strmConfig.filterFileTypes" type="textarea" :rows="2"
            placeholder="需要生成STRM文件的媒体文件类型，例如：mp4,mkv,flv,avi,wmv,ts,rmvb,webm,mp3,flac,aac,wav,ogg"
            autocomplete="off" />
        </el-form-item>
        <el-form-item label="编码路径" label-width="140">
          <el-switch v-model="form.strmConfig.encodePath" />
        </el-form-item>
        <el-form-item label="不包含URL" label-width="140">
          <el-switch v-model="form.strmConfig.withoutUrl" />
        </el-form-item>
        <el-form-item label="带签名" label-width="140">
          <el-switch v-model="form.strmConfig.withSign" :disabled="aListLoginEnabled" />
        </el-form-item>
        <el-form-item label="保存STRM到本地" label-width="140">
          <el-switch v-model="form.strmConfig.saveStrmToLocal" />
        </el-form-item>
        <el-form-item v-if="form.strmConfig.saveStrmToLocal" label="本地保存路径" label-width="140">
          <el-input v-model="form.strmConfig.saveStrmLocalPath" autocomplete="off"
            placeholder="本地保存的路径，例如：local_strm (如果不以/开头，将自动补充/data/前缀)" />
        </el-form-item>
        <el-form-item v-if="form.strmConfig.saveStrmToLocal" label="保存模式" label-width="140">
          <el-select v-model="form.strmConfig.saveLocalMode" placeholder="选择保存模式">
            <el-option label="新增模式" value="insert">
              <span>新增模式</span>
              <span style="color: var(--el-text-color-secondary); font-size: 12px; margin-left: 8px;">
                仅对本地没有的文件进行生成
              </span>
            </el-option>
            <el-option label="更新模式" value="update">
              <span>更新模式</span>
              <span style="color: var(--el-text-color-secondary); font-size: 12px; margin-left: 8px;">
                生成新文件并更新已有文件
              </span>
            </el-option>
            <el-option label="同步模式" value="sync">
              <span>同步模式</span>
              <span style="color: var(--el-text-color-secondary); font-size: 12px; margin-left: 8px;">
                完全同步，删除网盘中不存在的本地文件
              </span>
            </el-option>
          </el-select>
          <div style="color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; line-height: 1.5;">
            💡 <strong>新增模式</strong>: 仅对本地没有的文件进行生成，对本地文件不进行任何操作<br>
            💡 <strong>更新模式</strong>: 对本地没有的文件进行生成同时更新本地文件内容至最新<br>
            💡 <strong>同步模式</strong>: 在更新模式的基础上删除本地中网盘没有的文件<br>
            <span style="color: var(--el-color-warning);">⚠️ 推荐:
              如果使用刮削器等软件读取本地strm文件并生成元数据，请选择<strong>更新模式</strong>，以确保本地strm文件内容是最新的且不会删除元数据文件</span>
          </div>
        </el-form-item>
      </template>

      <el-form-item label="类型" label-width="140">
        <el-radio-group v-model="form.type" class="ml-4">
          <el-radio :label="0" size="large">阿里分享</el-radio>
          <el-radio :label="1" size="large">PikPak分享</el-radio>
          <el-radio :label="5" size="large">夸克分享</el-radio>
          <el-radio :label="7" size="large">UC分享</el-radio>
          <el-radio :label="8" size="large">115分享</el-radio>
          <el-radio :label="9" size="large">天翼分享</el-radio>
          <el-radio :label="6" size="large">移动分享</el-radio>
          <el-radio :label="2" size="large">迅雷分享</el-radio>
          <el-radio :label="3" size="large">123分享</el-radio>
          <el-radio :label="10" size="large">百度分享</el-radio>
          <el-radio :label="12" size="large">光鸭分享</el-radio>
          <el-radio :label="4" size="large">本地存储</el-radio>
          <el-radio :label="11" size="large">STRM存储</el-radio>
        </el-radio-group>
      </el-form-item>
      <span v-if="form.path">完整路径： {{ fullPath(form) }}</span>
      <div>网盘帐号在帐号页面添加。</div>
    </el-form>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
    </template>
  </el-dialog>

  <el-dialog v-model="dialogVisible" title="删除资源" width="30%">
    <div v-if="batch">
      <p>是否删除选中的{{ multipleSelection.length }}个资源?</p>
    </div>
    <div v-else>
      <p>是否删除资源 - {{ form.shareId }}</p>
      <p>{{ form.path }}</p>
    </div>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteSub">删除</el-button>
      </span>
    </template>
  </el-dialog>

  <el-dialog v-model="dialogVisible1" title="删除资源" width="30%">
    <div v-if="selectedStorages.length">
      <p>是否删除选中的{{ selectedStorages.length }}个资源?</p>
    </div>
    <div v-else>
      <p>是否删除资源 - {{ storage.id }}</p>
      <p>{{ storage.mount_path }}</p>
    </div>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible1 = false">取消</el-button>
        <el-button type="danger" @click="deleteStorage">删除</el-button>
      </span>
    </template>
  </el-dialog>

  <el-dialog v-model="uploadVisible" title="导入分享" width="60%">
    <el-form label-width="140">
      <el-form-item label="类型">
        <el-radio-group v-model="sharesDto.type" class="ml-4">
          <el-radio label="-1" size="large">自动</el-radio>
          <el-radio label="ali" size="large">阿里分享</el-radio>
          <el-radio label="pikpak" size="large">PikPak分享</el-radio>
          <el-radio label="quark" size="large">夸克分享</el-radio>
          <el-radio label="uc" size="large">UC分享</el-radio>
          <el-radio label="115" size="large">115分享</el-radio>
          <el-radio label="189" size="large">天翼分享</el-radio>
          <el-radio label="139" size="large">移动分享</el-radio>
          <el-radio label="thunder" size="large">迅雷分享</el-radio>
          <el-radio label="123" size="large">123分享</el-radio>
          <el-radio label="baidu" size="large">百度分享</el-radio>
          <el-radio label="duck" size="large">光鸭分享</el-radio>
          <el-radio label="strm" size="large">STRM存储</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="导入延迟(毫秒)">
        <el-input-number v-model="sharesDto.delay" :min="0" :step="100" controls-position="right"
          style="width: 200px;" />
        <span class="hint">每个分享导入后等待的毫秒数（0表示无延迟）</span>
      </el-form-item>
      <el-form-item label="分享内容">
        <el-input v-model="sharesDto.content" type="textarea" :rows="15"
          :placeholder="'多行分享\n格式1：挂载路径 分享ID 目录ID 提取码\n格式2：挂载路径 分享链接\n格式3：挂载路径 分享链接 root 提取码'" />
      </el-form-item>
      <el-form-item label="导入文件">
        <el-upload ref="upload" action="/api/import-share-file" accept="text/plain" class="upload" :limit="1"
          :on-exceed="handleExceed" :on-success="onUploadSuccess" :on-error="onUploadError"
          :on-change="handleFileChange"
          :headers="{ 'authorization': token }" :data="{ type: sharesDto.type, delay: sharesDto.delay }"
          :auto-upload="false">
          <template #trigger>
            <el-button type="primary" :disabled="uploading">选择文件</el-button>
          </template>
          <span class="hint"></span>
          <el-button type="success" :disabled="uploading || !hasFile" @click="submitUpload">
            上传导入
          </el-button>
          <template #tip>
            <div class="el-upload__tip">
              上传分享列表文件，最大20MB
            </div>
          </template>
        </el-upload>
      </el-form-item>
      <el-progress v-if="uploading" :percentage="100" status="success" :indeterminate="true" :duration="5" />
    </el-form>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button class="ml-3" type="success" :disabled="uploading || !hasContent" @click="importShares">导入</el-button>
      </span>
    </template>
  </el-dialog>

  <el-dialog v-model="exportVisible" title="导出分享" width="60%">
    <el-form-item label="类型" label-width="140">
      <el-radio-group v-model="exportDrive" class="ml-4">
        <el-radio label="-1" size="large">全部</el-radio>
        <el-radio label="ali" size="large">阿里分享</el-radio>
        <el-radio label="pikpak" size="large">PikPak分享</el-radio>
        <el-radio label="quark" size="large">夸克分享</el-radio>
        <el-radio label="uc" size="large">UC分享</el-radio>
        <el-radio label="115" size="large">115分享</el-radio>
        <el-radio label="189" size="large">天翼分享</el-radio>
        <el-radio label="139" size="large">移动分享</el-radio>
        <el-radio label="thunder" size="large">迅雷分享</el-radio>
        <el-radio label="123" size="large">123分享</el-radio>
        <el-radio label="baidu" size="large">百度分享</el-radio>
        <el-radio label="duck" size="large">光鸭分享</el-radio>
        <el-radio label="strm" size="large">STRM</el-radio>
      </el-radio-group>
    </el-form-item>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="exportVisible = false">取消</el-button>
        <el-button class="ml-3" type="success" @click="exportShares">导出</el-button>
      </span>
    </template>
  </el-dialog>

</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import axios from "axios";
import { ElMessage } from 'element-plus'
import { genFileId } from 'element-plus'
import type { UploadInstance, UploadProps, UploadRawFile } from 'element-plus'
const upload = ref<UploadInstance>()
import accountService from "@/services/account.service";
import { Search } from "@element-plus/icons-vue";
import { store } from "@/services/store";

const token = accountService.getToken()

interface StrmConfig {
  paths: string
  siteUrl: string
  pathPrefix: string
  downloadFileTypes: string
  filterFileTypes: string
  encodePath: boolean
  withoutUrl: boolean
  withSign: boolean
  saveStrmToLocal: boolean
  saveStrmLocalPath: string
  saveLocalMode: string
}

interface ShareInfo {
  id: string
  path: string
  shareId: string
  folderId: string
  password: string
  cookie: string
  status: string
  type: number
  strmConfig?: StrmConfig
}

interface Storage {
  id: number
  mount_path: string
  driver: string
  status: string
  addition: string
}

const options = [
  { label: '全部', value: '-1' },
  { label: '夸克', value: 'quark' },
  { label: 'UC', value: 'uc' },
  { label: '阿里', value: 'ali' },
  { label: '115', value: '115' },
  { label: '123', value: '123' },
  { label: '天翼', value: '189' },
  { label: '百度', value: 'baidu' },
  { label: '光鸭', value: 'duck' },
  { label: '迅雷', value: 'thunder' },
  { label: '移动', value: '139' },
  { label: 'PikPak', value: 'pikpak' },
  { label: '本地', value: 'local' },
  { label: 'STRM', value: 'strm' },
]

const driveByType: Record<number, string> = {
  0: 'ali',
  1: 'pikpak',
  2: 'thunder',
  3: '123',
  4: 'local',
  5: 'quark',
  6: '139',
  7: 'uc',
  8: '115',
  9: '189',
  10: 'baidu',
  11: 'strm',
  12: 'duck'
}

const shareTypeMeta: Record<string, { label: string; mountPrefix?: string; linkPrefix?: string; passwordParam?: string; password?: boolean }> = {
  ali: { label: '阿里分享', mountPrefix: '/\uD83C\uDE34我的阿里分享/', linkPrefix: 'https://www.alipan.com/s/', passwordParam: 'password' },
  pikpak: { label: 'PikPak分享', mountPrefix: '/\uD83D\uDD78\uFE0F我的PikPak分享/', linkPrefix: 'https://mypikpak.com/s/', passwordParam: 'pwd' },
  thunder: { label: '迅雷分享', mountPrefix: '/我的迅雷分享/', linkPrefix: 'https://pan.xunlei.com/s/', passwordParam: 'pwd' },
  '123': { label: '123分享', mountPrefix: '/我的123分享/', linkPrefix: 'https://www.123pan.com/s/', passwordParam: 'password' },
  local: { label: '本地存储' },
  quark: { label: '夸克分享', mountPrefix: '/我的夸克分享/', linkPrefix: 'https://pan.quark.cn/s/', passwordParam: 'password' },
  '139': { label: '移动分享', mountPrefix: '/我的移动分享/', linkPrefix: 'https://caiyun.139.com/m/i?', passwordParam: 'password' },
  uc: { label: 'UC分享', mountPrefix: '/我的UC分享/', linkPrefix: 'https://fast.uc.cn/s/', passwordParam: 'password' },
  '115': { label: '115分享', mountPrefix: '/我的115分享/', linkPrefix: 'https://115.com/s/', passwordParam: 'password' },
  '189': { label: '天翼分享', mountPrefix: '/我的天翼分享/', linkPrefix: 'https://cloud.189.cn/t/', passwordParam: 'password' },
  baidu: { label: '百度分享', mountPrefix: '/我的百度分享/', linkPrefix: 'https://pan.baidu.com/s/', passwordParam: 'pwd' },
  strm: { label: 'STRM存储' },
  duck: { label: '光鸭分享', mountPrefix: '/我的光鸭分享/', linkPrefix: 'https://www.guangyapan.com/s/' }
}

const getDrive = (type: number) => driveByType[type] || 'ali'
const getShareTypeLabel = (type: number) => shareTypeMeta[getDrive(type)]?.label || shareTypeMeta.ali.label

const multipleSelection = ref<ShareInfo[]>([])
const storages = ref<Storage[]>([])
const loadingStorages = ref(false)
const selectedStorages = ref<Storage[]>([])
const storage = ref<Storage>({
  id: 0,
  mount_path: '',
  driver: '',
  status: '',
  addition: ''
})
const sort = ref('')
const page = ref(1)
const page1 = ref(1)
const size = ref(20)
const type = ref('-1')
const size1 = ref(20)
const total = ref(0)
const total1 = ref(0)
const shares = ref([])
const loading = ref(false)
const keyword = ref('')
const dialogTitle = ref('')
const formVisible = ref(false)
const uploadVisible = ref(false)
const uploading = ref(false)
const exportVisible = ref(false)
const dialogVisible = ref(false)
const dialogVisible1 = ref(false)
const updateAction = ref(false)
const batch = ref(false)
const aListLoginEnabled = ref(false)
const detectedStrmSiteUrl = ref('')
const applyStrmAutoSign = (strmConfig: StrmConfig) => {
  if (aListLoginEnabled.value) {
    strmConfig.withSign = true
  }
  return strmConfig
}
const applyStrmSiteUrl = (strmConfig: StrmConfig) => {
  if (!strmConfig.siteUrl) {
    strmConfig.siteUrl = detectedStrmSiteUrl.value
  }
  return strmConfig
}
const createStrmConfig = (encodePath = false) => applyStrmAutoSign({
  paths: '',
  siteUrl: detectedStrmSiteUrl.value,
  pathPrefix: '/d',
  downloadFileTypes: 'ass,srt,vtt,sub,strm',
  filterFileTypes: 'mp4,mkv,flv,avi,wmv,ts,rmvb,webm,mp3,flac,aac,wav,ogg,m4a,wma,alac',
  encodePath,
  withoutUrl: false,
  withSign: false,
  saveStrmToLocal: false,
  saveStrmLocalPath: '',
  saveLocalMode: 'update'
})
const form = ref<ShareInfo>({
  id: '',
  path: '',
  shareId: '',
  folderId: '',
  password: '',
  cookie: '',
  status: '',
  type: -1,
  strmConfig: createStrmConfig(true)
})
const sharesDto = ref({
  content: '',
  type: '-1',
  delay: 0
})
const exportDrive = ref('-1')
const selectedFile = ref<UploadRawFile | null>(null)

const hasContent = computed(() => sharesDto.value.content.trim().length > 0)
const hasFile = computed(() => selectedFile.value !== null)

const handleAdd = () => {
  dialogTitle.value = '添加分享'
  updateAction.value = false
  form.value = {
    id: '',
    path: '',
    shareId: '',
    folderId: '',
    password: '',
    cookie: '',
    status: '',
    type: 0,
    strmConfig: createStrmConfig()
  }
  formVisible.value = true
}

const handleEdit = (data: ShareInfo) => {
  dialogTitle.value = '更新分享 - ' + data.id
  updateAction.value = true
  // Parse STRM config from folderId if it's STRM type
  let strmConfig = createStrmConfig()
  if (data.type === 11 && data.cookie) {
    try {
      strmConfig = applyStrmAutoSign({ ...createStrmConfig(), ...JSON.parse(data.cookie) })
    } catch (e) {
      ElMessage.error('解析STRM配置失败')
    }
  }
  strmConfig = applyStrmSiteUrl(strmConfig)
  form.value = {
    id: data.id,
    path: data.path,
    shareId: data.shareId,
    folderId: data.folderId,
    password: data.password,
    cookie: data.cookie,
    status: data.status,
    type: data.type,
    strmConfig: strmConfig
  }
  formVisible.value = true
}

const handleDelete = (data: ShareInfo) => {
  batch.value = false
  form.value = data
  dialogVisible.value = true
}

const handleDeleteStorage = (data: Storage) => {
  storage.value = data
  dialogVisible1.value = true
}

const handleDeleteBatch = () => {
  batch.value = true
  dialogVisible.value = true
}

const deleteSub = () => {
  dialogVisible.value = false
  if (batch.value) {
    axios.post('/api/delete-shares', multipleSelection.value.map(s => s.id)).then(() => {
      loadShares(page.value)
    })
  } else {
    axios.delete('/api/shares/' + form.value.id).then(() => {
      loadShares(page.value)
    })
  }
}

const deleteStorage = () => {
  dialogVisible1.value = false
  if (selectedStorages.value.length) {
    axios.post('/api/delete-shares', selectedStorages.value.map(s => s.id)).then(() => {
      loadStorages(page1.value)
    })
  } else {
    axios.delete('/api/shares/' + storage.value.id).then(() => {
      loadStorages(page1.value)
    })
  }
}

const handleCancel = () => {
  formVisible.value = false
}

const fullPath = (share: any) => {
  const path = share.path;
  if (path.startsWith('/')) {
    return path
  }
  const drive = getDrive(share.type)
  if (drive === 'local') {
    return path
  } else if (drive === 'strm') {
    return path.startsWith("/") ? path : "/strm/" + path;
  }
  return (shareTypeMeta[drive]?.mountPrefix || shareTypeMeta.ali.mountPrefix) + path
}

const handleConfirm = () => {
  if (form.value.type === 11 && form.value.strmConfig) {
    form.value.strmConfig = applyStrmSiteUrl(applyStrmAutoSign(form.value.strmConfig))
  }

  // 如果是STRM存储，且保存到本地，且本地保存路径不以/开头，则自动补充/data/前缀
  if (
    form.value.type === 11 &&
    form.value.strmConfig?.saveStrmToLocal &&
    form.value.strmConfig?.saveStrmLocalPath &&
    !form.value.strmConfig.saveStrmLocalPath.startsWith("/")
  ) {
    form.value.strmConfig.saveStrmLocalPath = "/data/" + form.value.strmConfig.saveStrmLocalPath;
  }

  axios.post('/api/shares/' + form.value.id, form.value).then(() => {
    formVisible.value = false
    loadShares(page.value)
  })
}

const getShareLink = (shareInfo: ShareInfo) => {
  const drive = getDrive(shareInfo.type)
  const meta = shareTypeMeta[drive]
  if (!meta?.linkPrefix) {
    return ''
  }

  let url = meta.linkPrefix + shareInfo.shareId
  if (drive === 'ali') {
    if (shareInfo.folderId) {
      url = url + '/folder/' + shareInfo.folderId
    }
  }
  if (shareInfo.password && meta.passwordParam) {
    url = url + '?' + meta.passwordParam + '=' + shareInfo.password
  }
  return url
}

const filter = () => {
  loadShares(1)
}

const search = () => {
  loadShares(1)
}

const loadShares = (value: number) => {
  page.value = value
  loading.value = true
  axios.get('/api/shares?page=' + (page.value - 1) + '&size=' + size.value + '&sort=' + sort.value + '&type=' + type.value + '&keyword=' + keyword.value).then(({ data }) => {
    shares.value = data.content
    total.value = data.totalElements
  }).finally(() => {
    loading.value = false
  })
}

const loadStorages = (value: number) => {
  page1.value = value
  loadingStorages.value = true
  axios.get('/api/storages?page=' + page1.value + '&size=' + size1.value).then(({ data }) => {
    storages.value = data.data.content
    total1.value = data.data.total
  }).finally(() => {
    loadingStorages.value = false
  })
}

const cleanStorages = () => {
  axios.delete('/api/storages').then(({ data }) => {
    ElMessage.success(`删除${data}个失效资源`)
    loadStorages(1)
  })
}

const validateStorages = () => {
  axios.post('/api/storages').then(() => {
    ElMessage.success('开始校验')
  })
}

const deleteShares = () => {
  axios.delete('/api/shares').then(({ data }) => {
    ElMessage.success(`成功删除${data}个资源`)
    loadShares(1)
  })
}

const reloadStorage = (id: number) => {
  axios.post('/api/storages/' + id).then(({ data }) => {
    if (data.code == 200) {
      ElMessage.success('加载成功')
      loadStorages(page1.value)
    } else {
      ElMessage.error(data.message)
    }
  })
}

const refreshShares = () => {
  loadShares(page.value)
}

const refreshStorages = () => {
  loadStorages(page1.value)
}

const handleSizeChange = (value: number) => {
  size.value = value
  page.value = 1
  axios.get('/api/shares?page=' + (page.value - 1) + '&size=' + size.value + '&type=' + type.value).then(({ data }) => {
    shares.value = data.content
    total.value = data.totalElements
  })
}

const handleSize1Change = (value: number) => {
  size1.value = value
  loadStorages(1)
}

// const reload = () => {
//   axios.post('/api/tacit0924').then(() => {
//     ElMessage.success('更新成功')
//     loadShares(page.value)
//   })
// }

const showUpload = () => {
  uploadVisible.value = true
  if (upload.value) {
    upload.value!.clearFiles()
  }
  selectedFile.value = null
}

const handleExceed: UploadProps['onExceed'] = (files) => {
  upload.value!.clearFiles()
  const file = files[0] as UploadRawFile
  file.uid = genFileId()
  upload.value!.handleStart(file)
  selectedFile.value = file
}

const handleFileChange: UploadProps['onChange'] = (uploadFile, uploadFiles) => {
  if (uploadFiles.length > 0 && uploadFiles[0].raw) {
    selectedFile.value = uploadFiles[0].raw
  } else {
    selectedFile.value = null
  }
}

const onUploadSuccess: UploadProps['onSuccess'] = (data) => {
  uploadSuccess(data)
}

const onUploadError: UploadProps['onError'] = (err) => {
  uploadError(err)
}

const submitUpload = () => {
  uploading.value = true
  upload.value!.submit()
}

const importShares = () => {
  uploading.value = true
  axios.post('/api/import-shares', sharesDto.value).then(({ data }) => {
    uploadSuccess(data)
  }, (err) => {
    uploadError(err)
  })
}

const exportShares = () => {
  window.location.href = '/api/export-shares?type=' + exportDrive.value + '&t=' + new Date().getTime() + '&X-ACCESS-TOKEN=' + localStorage.getItem("token");
}

const uploadSuccess = (response: any) => {
  uploading.value = false
  uploadVisible.value = false
  sharesDto.value.content = ''
  loadShares(page.value)
  ElMessage.success('成功导入' + response + '个分享')
}

const uploadError = (error: Error) => {
  uploading.value = false
  ElMessage.error('导入失败：' + error)
}

const handleSort = (data: { prop: string, order: any }) => {
  if (data.order) {
    sort.value = data.prop + ',' + (data.order === 'ascending' ? 'asc' : 'desc')
  } else {
    sort.value = data.prop
  }
  loadShares(page.value)
}

const handleSelection = (val: ShareInfo[]) => {
  multipleSelection.value = val
}

const handleSelectionStorages = (val: Storage[]) => {
  selectedStorages.value = val
}

const updateDetectedStrmSiteUrl = (url: string) => {
  detectedStrmSiteUrl.value = url
  if (form.value.type === 11 && form.value.strmConfig && !form.value.strmConfig.siteUrl) {
    form.value.strmConfig.siteUrl = url
  }
}

const loadBaseUrl = () => {
  if (store.baseUrl) {
    updateDetectedStrmSiteUrl(store.baseUrl)
    return
  }

  const fallback = window.location.protocol + '//' + window.location.hostname + ':' + (store.hostmode ? 5678 : 5344)
  updateDetectedStrmSiteUrl(fallback)

  if (!store.admin) {
    return
  }

  axios.get('/api/sites/1').then(({ data }) => {
    let url = data.url
    const re = /http:\/\/localhost:(\d+)/.exec(data.url)
    if (re) {
      url = window.location.protocol + '//' + window.location.hostname + ':' + re[1]
      store.baseUrl = url
      updateDetectedStrmSiteUrl(url)
    } else if (data.url == 'http://localhost') {
      axios.get('/api/alist/port').then(({ data }) => {
        if (data) {
          url = window.location.protocol + '//' + window.location.hostname + ':' + data
          store.baseUrl = url
          updateDetectedStrmSiteUrl(url)
        }
      })
    } else {
      store.baseUrl = url
      updateDetectedStrmSiteUrl(url)
    }
  })
}

onMounted(() => {
  axios.get('/api/settings').then(({ data }) => {
    aListLoginEnabled.value = data.alist_login === 'true'
    if (form.value.type === 11 && form.value.strmConfig) {
      form.value.strmConfig = applyStrmAutoSign(form.value.strmConfig)
    }
  })
  loadBaseUrl()
  loadShares(page.value)
  loadStorages(page1.value)
})
</script>

<style scoped>
.space {
  margin: 12px 0;
}

.upload {
  width: 50%;
}
</style>
