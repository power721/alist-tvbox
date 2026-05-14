import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const viewSource = readFileSync(new URL('./SubscriptionsView.vue', import.meta.url), 'utf8')

test('does not show small-screen plugin drag hint text', () => {
  assert.equal(viewSource.includes('小屏幕不支持拖拽排序，请在大屏设备操作'), false)
})
