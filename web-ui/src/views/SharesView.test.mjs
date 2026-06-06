import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const viewSource = readFileSync(new URL('./SharesView.vue', import.meta.url), 'utf8')

test('shares page supports GuangYa in filters and resource forms', () => {
  assert.equal(viewSource.includes(`{ label: '光鸭', value: 12 }`), true)
  assert.equal(viewSource.includes(`<el-radio :label="12" size="large">光鸭分享</el-radio>`), true)
  assert.equal(viewSource.includes(`https://www.guangyapan.com/s/{{ scope.row.shareId }}`), true)
  assert.equal(viewSource.includes(`return '/我的光鸭分享/' + path`), true)
})

test('shares page shows STRM sign enabled when AList login is forced', () => {
  assert.equal(viewSource.includes(`const aListLoginEnabled = ref(false)`), true)
  assert.equal(viewSource.includes(`aListLoginEnabled.value = data.alist_login === 'true'`), true)
  assert.equal(viewSource.includes(`const applyStrmAutoSign = (strmConfig: StrmConfig)`), true)
  assert.equal(viewSource.includes(`strmConfig.withSign = true`), true)
  assert.equal(viewSource.includes(`form.value.strmConfig = applyStrmAutoSign(form.value.strmConfig)`), true)
})

test('shares page auto-detects STRM site URL from default AList site', () => {
  assert.equal(viewSource.includes(`import { store } from "@/services/store";`), true)
  assert.equal(viewSource.includes(`const detectedStrmSiteUrl = ref('')`), true)
  assert.equal(viewSource.includes(`siteUrl: detectedStrmSiteUrl.value`), true)
  assert.equal(viewSource.includes(`axios.get('/api/sites/1')`), true)
  assert.equal(viewSource.includes(`axios.get('/api/alist/port')`), true)
  assert.equal(viewSource.includes(`store.baseUrl = url`), true)
})
