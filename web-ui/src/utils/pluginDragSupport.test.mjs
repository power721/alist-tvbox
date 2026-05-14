import test from 'node:test'
import assert from 'node:assert/strict'

import { isPluginDragEnabledForUserAgent } from './pluginDragSupport.mjs'

test('disables plugin drag sorting for Android browsers', () => {
  assert.equal(
    isPluginDragEnabledForUserAgent('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36'),
    false
  )
})

test('disables plugin drag sorting for Apple mobile browsers', () => {
  assert.equal(
    isPluginDragEnabledForUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 Version/17.4 Mobile/15E148 Safari/604.1'),
    false
  )
  assert.equal(
    isPluginDragEnabledForUserAgent('Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 Version/17.4 Mobile/15E148 Safari/604.1'),
    false
  )
})

test('keeps plugin drag sorting enabled for desktop browsers', () => {
  assert.equal(
    isPluginDragEnabledForUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36'),
    true
  )
  assert.equal(
    isPluginDragEnabledForUserAgent('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.4 Safari/605.1.15'),
    true
  )
})
