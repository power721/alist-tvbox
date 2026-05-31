import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const componentSource = readFileSync(new URL('./SearchView.vue', import.meta.url), 'utf8')

test('search page disables search button while searching', () => {
  assert.equal(componentSource.includes(`const searching = ref(false)`), true)
  assert.equal(componentSource.includes(`:disabled="!keyword || searching"`), true)
  assert.equal(componentSource.includes(`if (searching.value) {`), true)
  assert.equal(componentSource.includes(`.finally(() => {`), true)
})

test('search page persists search keyword in localStorage', () => {
  assert.equal(componentSource.includes(`const keyword = ref(localStorage.getItem("search_keyword") || '')`), true)
  assert.equal(componentSource.includes(`localStorage.setItem('search_keyword', keyword.value.trim())`), true)
})

test('PanSou search result table supports local header sorting', () => {
  assert.equal(componentSource.includes(`<el-table v-if="(type=='6')&&config" :data="filteredPanSouResults" border style="width: 100%" v-loading="searching">`), true)
  assert.equal(componentSource.includes(`<el-table-column prop="vod_name" label="名称" sortable>`), true)
  assert.equal(componentSource.includes(`<el-table-column prop="vod_id" label="链接" width="350" sortable>`), true)
  assert.equal(componentSource.includes(`<el-table-column prop="vod_remarks" label="类型" width="100" sortable/>`), true)
})

test('PanSou search result page supports filtering by result type', () => {
  assert.equal(componentSource.includes(`const panSouType = ref('ALL')`), true)
  assert.equal(componentSource.includes(`const panSouTypeOptions = computed(() => {`), true)
  assert.equal(componentSource.includes(`const filteredPanSouResults = computed(() => {`), true)
  assert.equal(componentSource.includes(`v-model="panSouType"`), true)
  assert.equal(componentSource.includes(`filteredPanSouResults.length`), true)
})

test('PanSou search result table displays source and update time', () => {
  assert.equal(componentSource.includes(`<el-table-column prop="vod_play_from" label="来源" width="180" sortable/>`), true)
  assert.equal(componentSource.includes(`<el-table-column prop="vod_time" label="时间" width="180" sortable/>`), true)
})

test('PanSou search result page can check visible link validity', () => {
  assert.equal(componentSource.includes(`检测有效性`), true)
  assert.equal(componentSource.includes(`const checkingLinks = ref(false)`), true)
  assert.equal(componentSource.includes(`const diskTypeMap`), true)
  assert.equal(componentSource.includes(`'光鸭': 'guangya'`), true)
  assert.equal(componentSource.includes(`'光鸭云盘': 'guangya'`), true)
  assert.equal(componentSource.includes(`axios.post('/api/pansou/check/links'`), true)
  assert.equal(componentSource.includes(`prop="validity_summary" label="有效性"`), true)
})

test('PanSou search result table has per-row validity check button', () => {
  assert.equal(componentSource.includes(`<el-table-column label="操作" width="90">`), true)
  assert.equal(componentSource.includes(`:disabled="!isCheckSupportedRow(scope.row)"`), true)
  assert.equal(componentSource.includes(`@click="checkLink(scope.row)"`), true)
  assert.equal(componentSource.includes(`const checkLink = (row: any) => {`), true)
  assert.equal(componentSource.includes(`row.validity_checking = true`), true)
  assert.equal(componentSource.includes(`const isCheckSupportedRow = (row: any) => {`), true)
})

test('PanSou search result page has concurrent chunked validity check mode', () => {
  assert.equal(componentSource.includes(`并发检测`), true)
  assert.equal(componentSource.includes(`const checkLinksConcurrently = () => {`), true)
  assert.equal(componentSource.includes(`const chunkSize = 5`), true)
  assert.equal(componentSource.includes(`Promise.all(chunks.map`), true)
})

test('PanSou search result table hides magnet dn parameter only for display', () => {
  assert.equal(componentSource.includes(`{{ formatDisplayLink(scope.row.vod_id) }}`), true)
  assert.equal(componentSource.includes(`const formatDisplayLink = (vodId: string) => {`), true)
  assert.equal(componentSource.includes(`url.searchParams.delete('dn')`), true)
  assert.equal(componentSource.includes(`return decodeURIComponent(vodId)`), true)
})
