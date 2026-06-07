import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const viewSource = readFileSync(new URL('./VodView.vue', import.meta.url), 'utf8')

test('vod search type selector includes magnet and ed2k filters', () => {
  assert.equal(viewSource.includes(`{label: '光鸭', value: '12'}`), true)
  assert.equal(viewSource.includes(`if (type == '12') {`), true)
  assert.equal(viewSource.includes(`{label: '磁力', value: 'magnet'}`), true)
  assert.equal(viewSource.includes(`{label: 'ED2K', value: 'ed2k'}`), true)
})

test('vod search separates public and private telegram search endpoints', () => {
  assert.equal(viewSource.includes(`const searchMode = ref('public')`), true)
  assert.equal(viewSource.includes(`label="盘搜"`), true)
  assert.equal(viewSource.includes(`label="电报频道"`), true)
  assert.equal(viewSource.includes(`searchMode.value === 'private' ? '/api/telegram/private/search' : '/api/telegram/search'`), true)
  assert.equal(viewSource.includes(`encodeURIComponent(keyword.value)`), true)
})
