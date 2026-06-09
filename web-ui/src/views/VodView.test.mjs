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

test('vod page keeps telegram search tab out of player page', () => {
  assert.equal(viewSource.includes(`const searchMode = ref('public')`), false)
  assert.equal(viewSource.includes(`label="电报频道"`), false)
  assert.equal(viewSource.includes(`searchMode.value === 'private' ? '/api/telegram/private/search' : '/api/telegram/search'`), false)
  assert.equal(viewSource.includes(`axios.get('/api/telegram/search?wd=' + encodeURIComponent(keyword.value))`), true)
  assert.equal(viewSource.includes(`encodeURIComponent(keyword.value)`), true)
})
