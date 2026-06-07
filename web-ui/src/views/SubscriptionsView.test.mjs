import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const viewSource = readFileSync(new URL('./SubscriptionsView.vue', import.meta.url), 'utf8')

test('does not show small-screen plugin drag hint text', () => {
  assert.equal(viewSource.includes('小屏幕不支持拖拽排序，请在大屏设备操作'), false)
})

test('edits source extend with dialog button instead of inline table input', () => {
  assert.equal(viewSource.includes('v-model="scope.row.extend"'), false)
  assert.equal(viewSource.includes('@click="openSourceExtendDialog(scope.row)"'), true)
})

test('reorders unified subscription sources instead of plugin-only list', () => {
  assert.equal(viewSource.includes("/api/subscription-sources/reorder"), true)
})

test('exposes plugin run mode settings in subscription source manager', () => {
  assert.equal(viewSource.includes('plugin_run_mode'), true)
  assert.equal(viewSource.includes('原生Python'), true)
  assert.equal(viewSource.includes('Java代理'), true)
})

test('does not show telegram login button in subscription toolbar', () => {
  assert.equal(viewSource.includes('<el-button @click="handleLogin">登录 Telegram</el-button>'), false)
})

test('does not own telegram sms login workflow', () => {
  assert.equal(viewSource.includes('/api/telegram/login/send-code'), false)
  assert.equal(viewSource.includes('/api/telegram/login/sign-in'), false)
  assert.equal(viewSource.includes('/api/telegram/login/password'), false)
  assert.equal(viewSource.includes('/api/telegram/logout'), false)
})
