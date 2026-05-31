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
