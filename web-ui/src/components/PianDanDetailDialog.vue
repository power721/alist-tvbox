<template>
  <el-dialog :model-value="modelValue" title="媒体详情" width="860" append-to-body
             @update:model-value="emit('update:modelValue', $event)">
    <div class="nav-detail" v-loading="loading">
      <template v-if="detail">
        <el-image :src="poster" fit="cover" class="nav-detail-poster">
          <template #error>
            <div class="nav-detail-poster cover-placeholder">{{ (detail.vod_name || '?').charAt(0) }}</div>
          </template>
        </el-image>
        <div class="nav-detail-info">
          <div class="nav-detail-title">
            {{ detail.vod_name }}
            <span v-if="detail.vod_year" class="sub-text">({{ detail.vod_year }})</span>
          </div>
          <div class="nav-detail-tags">
            <el-tag v-for="genre in genres" :key="genre" effect="plain">{{ genre }}</el-tag>
            <el-tag v-if="detail.vod_remarks" type="warning">{{ detail.vod_remarks }}</el-tag>
            <el-tag v-for="season in seasons" :key="'s' + season" type="info">第{{ season }}季</el-tag>
          </div>
          <div v-if="detail.vod_director" class="sub-text">导演:{{ detail.vod_director }}</div>
          <div v-if="detail.vod_actor" class="sub-text">演员:{{ detail.vod_actor }}</div>
          <div v-if="detail.vod_area || detail.vod_lang" class="sub-text">
            <template v-if="detail.vod_area">{{ detail.vod_area }}</template>
            <template v-if="detail.vod_area && detail.vod_lang"> / </template>
            <template v-if="detail.vod_lang">{{ detail.vod_lang }}</template>
          </div>
          <div v-if="detail.vod_content" class="nav-detail-overview">{{ detail.vod_content }}</div>
          <div v-else class="sub-text">暂无简介</div>
        </div>
      </template>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <slot name="actions" :item="item"></slot>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 片单条目媒体详情对话框:打开即用传入条目数据垫底,后端 /api/media-subscriptions/navigation/detail
 * (tmdb: 直取 TMDB / db: 本地豆瓣库 / s: 名称富化;封面已包 /images 代理)回来整体替换。
 * 操作按钮(想看/追更/移出等)由宿主页经 #actions slot 注入 —— 两页(榜单浏览/想看队列)复用同一份渲染。
 */
import {computed, ref, watch} from 'vue'
import axios from 'axios'
import {ElMessage} from 'element-plus'

const props = defineProps({
  modelValue: {type: Boolean, default: false},
  /** 须含 vod_id(片单形态);vod_name/vod_pic/vod_year 作垫底展示 */
  item: {type: Object as () => Record<string, any> | null, default: null}
})
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const loading = ref(false)
const detail = ref<any>(null)
let seq = 0

const poster = computed(() => detail.value?.vod_pic || props.item?.vod_pic || '')
const genres = computed(() => {
  const list = String(detail.value?.type_name || '').split('/')
  return list.map((g: string) => g.trim()).filter(Boolean)
})
const seasons = computed(() => Array.isArray(detail.value?.ext) ? detail.value.ext : [])

watch(() => props.modelValue, (visible) => {
  if (!visible || !props.item?.vod_id) return
  detail.value = {vod_name: props.item.vod_name, vod_pic: props.item.vod_pic, vod_year: props.item.vod_year,
    type_name: props.item.type_name, vod_remarks: props.item.vod_remarks}
  loading.value = true
  const my = ++seq
  axios.get('/api/media-subscriptions/navigation/detail', {params: {id: props.item.vod_id}}).then(response => {
    if (my !== seq) return
    detail.value = response.data || null
  }).catch(() => ElMessage.error('媒体详情加载失败')).finally(() => {
    if (my === seq) loading.value = false
  })
})
</script>

<style scoped>
.sub-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 48px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-dark);
}

.nav-detail {
  display: flex;
  gap: 24px;
  min-height: 300px;
}

.nav-detail-poster {
  width: 220px;
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
  gap: 10px;
}

.nav-detail-info .sub-text {
  font-size: 15px;
  line-height: 1.6;
}

.nav-detail-title {
  font-size: 22px;
  font-weight: 600;
  line-height: 1.4;
}

.nav-detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.nav-detail-overview {
  font-size: 15px;
  line-height: 1.8;
  white-space: pre-wrap;
}
</style>
