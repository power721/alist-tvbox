import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const componentSource = readFileSync(new URL('./PlayConfig.vue', import.meta.url), 'utf8')

test('play config channel management gates drag sorting by mobile browser detection', () => {
  assert.equal(componentSource.includes(`import {isPluginDragEnabledForUserAgent} from "@/utils/pluginDragSupport.mjs";`), true)
  assert.equal(componentSource.includes('v-if="channelDragEnabled"'), true)
  assert.equal(componentSource.includes('if (!channelDragEnabled) {'), true)
})
