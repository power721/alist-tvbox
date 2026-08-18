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

test('disables source and filter drag sorting on mobile browsers', () => {
  assert.equal(viewSource.includes('isPluginDragEnabledForUserAgent'), true)
  assert.equal(viewSource.includes('const pluginDragEnabled = ref(isPluginDragEnabledForUserAgent(window.navigator.userAgent))'), true)
  assert.equal((viewSource.match(/if \(!pluginDragEnabled\.value\)/g) || []).length, 2)
  assert.equal((viewSource.match(/pluginSortable = null|pluginFilterSortable = null/g) || []).length, 2)
})

test('exposes plugin run mode settings in subscription source manager', () => {
  assert.equal(viewSource.includes('plugin_run_mode'), true)
  assert.equal(viewSource.includes('原生Python'), true)
  assert.equal(viewSource.includes('Java代理'), true)
})

test('accepts encrypted txt and raw Python plugin addresses', () => {
  assert.equal(viewSource.includes('placeholder="https://example.com/plugin.txt 或 plugin.py"'), true)
})

test('uses visual editor for subscription override instead of raw textarea', () => {
  assert.equal(viewSource.includes('SubscriptionConfigEditor'), true)
  assert.equal(viewSource.includes("openEditor(false)"), true)
})

test('keeps null benchmark success values pending', () => {
  assert.equal(viewSource.includes('const isBenchmarkPending = (result: any) => result?.success === null || result?.pending'), true)
  assert.equal(viewSource.includes('result.success !== undefined && result.success !== null'), true)
})

test('supports declarative list fields with per-item editors', () => {
  const editorSource = readFileSync(new URL('../components/PluginFilterConfigFieldEditor.vue', import.meta.url), 'utf8')
  assert.equal(editorSource.includes("field.type === 'list'"), true)
  assert.equal(editorSource.includes('addListItem'), true)
  assert.equal(editorSource.includes('removeListItem'), true)
  assert.equal(editorSource.includes('itemLabel'), true)
})

test('validates and normalizes list config fields', () => {
  assert.equal(viewSource.includes('resolveConfigListItems'), true)
  assert.equal(viewSource.includes("field.type === 'list'"), true)
  assert.equal(viewSource.includes('itemLabel: field?.itemLabel'), true)
})

test('offers qq music qr login from managed source row', () => {
  assert.equal(viewSource.includes('QqMusicQrLoginDialog'), true)
  assert.equal(viewSource.includes('isQqMusicSource'), true)
  assert.equal(viewSource.includes('@click="openQqMusicLogin(scope.row)"'), true)
  assert.equal(viewSource.includes("name.includes('QQ音乐')"), true)
})

test('qq music qr dialog allows switching login type while polling', () => {
  const dialogSource = readFileSync(new URL('../components/QqMusicQrLoginDialog.vue', import.meta.url), 'utf8')
  assert.equal(dialogSource.includes(':disabled="polling"'), false)
  assert.equal(dialogSource.includes('@change="startLogin"'), true)
})

test('qq music qr dialog polls backend and saves extend', () => {
  const dialogSource = readFileSync(new URL('../components/QqMusicQrLoginDialog.vue', import.meta.url), 'utf8')
  assert.equal(dialogSource.includes("'/api/qqmusic/login?type='"), true)
  assert.equal(dialogSource.includes("'/api/qqmusic/check?key='"), true)
  assert.equal(dialogSource.includes("'/api/subscription-sources/' + props.source.id"), true)
  assert.equal(dialogSource.includes('setInterval(check, 1500)'), true)
})
