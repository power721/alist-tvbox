import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const viewSource = readFileSync(new URL('./BiliBiliView.vue', import.meta.url), 'utf8')

test('bilibili channel management gates drag sorting by mobile browser detection', () => {
  assert.equal(viewSource.includes(`import {isPluginDragEnabledForUserAgent} from "@/utils/pluginDragSupport.mjs";`), true)
  assert.equal(viewSource.includes('v-if="channelDragEnabled"'), true)
  assert.equal(viewSource.includes('if (!channelDragEnabled) {'), true)
})
