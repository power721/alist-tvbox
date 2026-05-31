import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const viewSource = readFileSync(new URL('./DriverAccountView.vue', import.meta.url), 'utf8')

test('driver account proxy config includes GuangYa', () => {
  assert.equal(viewSource.includes(`type CloudDriveType = 'ALI' | 'QUARK' | 'UC' | 'PAN115' | 'PAN123' | 'PAN139' | 'BAIDU' | 'GUANGYA'`), true)
  assert.equal(viewSource.includes(`{key: 'GUANGYA', label: '光鸭云盘'}`), true)
  assert.equal(viewSource.includes(`GUANGYA: {enabled: true, concurrency: 4, chunk_size: 1024}`), true)
  assert.equal(viewSource.includes(`|| type == 'GUANGYA'`), true)
})
