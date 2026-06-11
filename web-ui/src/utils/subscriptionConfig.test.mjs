import test from 'node:test'
import assert from 'node:assert/strict'
import {
  parseOverride,
  detectFilterMode,
  disabledSiteKeys,
  whitelistKeys,
  disabledParseNames,
  siteOverrideMap,
  serialize,
  stringify,
  buildHeaderRows,
  buildLiveRows,
} from './subscriptionConfig.mjs'

test('parseOverride: empty -> {}', () => {
  assert.deepEqual(parseOverride(''), {})
  assert.deepEqual(parseOverride('   '), {})
})

test('parseOverride: invalid -> null', () => {
  assert.equal(parseOverride('{bad'), null)
})

test('detectFilterMode reads whitelist/blacklist/object', () => {
  assert.equal(detectFilterMode({ 'sites-whitelist': ['a'] }), 'whitelist')
  assert.equal(detectFilterMode({ 'sites-blacklist': ['a'] }), 'blacklist')
  assert.equal(detectFilterMode({ blacklist: { sites: ['a'] } }), 'blacklist')
  assert.equal(detectFilterMode({}), 'none')
})

test('disabledSiteKeys unions legacy + object', () => {
  assert.deepEqual(
    disabledSiteKeys({ 'sites-blacklist': ['a'], blacklist: { sites: ['b', 'a'] } }).sort(),
    ['a', 'b']
  )
})

test('disabledParseNames from blacklist.parses', () => {
  assert.deepEqual(disabledParseNames({ blacklist: { parses: ['虾米'] } }), ['虾米'])
})

test('siteOverrideMap reads name/order from config.sites', () => {
  const m = siteOverrideMap({ sites: [{ key: 'a', name: '改名', order: 100 }, { key: 'b' }] })
  assert.deepEqual(m.a, { name: '改名', order: 100 })
  assert.deepEqual(m.b, {})
})

test('serialize: blacklist mode writes blacklist.sites, migrates legacy, drops sites-blacklist', () => {
  const base = { 'sites-blacklist': ['x'], rules: [{ name: 'keepme' }] }
  const state = {
    filterMode: 'blacklist',
    sites: [
      { key: 'a', origin: 'upstream', enabled: true, name: 'A', originalName: 'A', order: '', isCustom: false },
      { key: 'b', origin: 'builtin', enabled: false, name: 'B', originalName: 'B', order: '', isCustom: false },
    ],
    parses: [{ name: '虾米', enabled: false, isCustom: false }],
    headers: [],
    lives: [],
    wallpaper: '',
    logo: '',
    flags: [],
    ads: [],
  }
  const out = serialize(base, state)
  assert.equal(out['sites-blacklist'], undefined)
  assert.deepEqual(out.blacklist.sites, ['b'])
  assert.deepEqual(out.blacklist.parses, ['虾米'])
  assert.deepEqual(out.rules, [{ name: 'keepme' }]) // unknown key preserved
  assert.equal(out.sites, undefined) // no rename/order overrides
})

test('serialize: whitelist mode writes sites-whitelist of enabled keys', () => {
  const state = {
    filterMode: 'whitelist',
    sites: [
      { key: 'a', origin: 'upstream', enabled: true, name: 'A', originalName: 'A', order: '', isCustom: false },
      { key: 'b', origin: 'plugin', enabled: false, name: 'B', originalName: 'B', order: '', isCustom: false },
    ],
    parses: [],
    headers: [],
    lives: [],
    wallpaper: '',
    logo: '',
    flags: [],
    ads: [],
  }
  const out = serialize({}, state)
  assert.deepEqual(out['sites-whitelist'], ['a'])
  assert.equal(out.blacklist, undefined)
})

test('serialize: upstream rename + order emit sites partial; custom site full', () => {
  const state = {
    filterMode: 'none',
    sites: [
      { key: 'a', origin: 'upstream', enabled: true, name: '新名', originalName: 'A', order: 50, isCustom: false },
      { key: 'mine', origin: 'custom', enabled: true, isCustom: true, name: '自定义', type: 3, api: 'csp_X', searchable: 1, quickSearch: 1, filterable: 1 },
    ],
    parses: [],
    headers: [],
    lives: [],
    wallpaper: 'http://w',
    logo: '',
    flags: ['x'],
    ads: [],
  }
  const out = serialize({}, state)
  assert.deepEqual(out.sites.find((s) => s.key === 'a'), { key: 'a', name: '新名', order: 50 })
  const custom = out.sites.find((s) => s.key === 'mine')
  assert.equal(custom.type, 3)
  assert.equal(custom.api, 'csp_X')
  assert.equal(out.wallpaper, 'http://w')
  assert.deepEqual(out.flags, ['x'])
})

test('stringify: empty object -> empty string', () => {
  assert.equal(stringify({}), '')
  assert.equal(stringify({ wallpaper: 'x' }), '{"wallpaper":"x"}')
})

test('buildHeaderRows: parses config.headers into editor rows with pairs', () => {
  const rows = buildHeaderRows({
    headers: [
      { host: 'www.javbus.com', header: { Referer: 'https://www.javbus.com/', Cookie: 'a=1' } },
    ],
  })
  assert.equal(rows.length, 1)
  assert.equal(rows[0].host, 'www.javbus.com')
  assert.equal(rows[0].pairs.length, 2)
  assert.deepEqual(rows[0].pairs[0], { name: 'Referer', value: 'https://www.javbus.com/' })
  assert.deepEqual(rows[0].pairs[1], { name: 'Cookie', value: 'a=1' })
})

test('buildHeaderRows: empty/missing -> []', () => {
  assert.deepEqual(buildHeaderRows({}), [])
  assert.deepEqual(buildHeaderRows({ headers: [] }), [])
  assert.deepEqual(buildHeaderRows({ headers: [null, 5] }), [])
})

test('serialize: headers pairs are written to config as header object', () => {
  const state = {
    filterMode: 'none',
    sites: [],
    parses: [],
    headers: [
      { host: 'www.javbus.com', pairs: [
        { name: 'Referer', value: 'https://www.javbus.com/' },
        { name: 'Cookie', value: 'a=1' },
      ] },
    ],
    lives: [],
    wallpaper: '',
    logo: '',
    flags: [],
    ads: [],
  }
  const out = serialize({}, state)
  assert.equal(out.headers.length, 1)
  assert.equal(out.headers[0].host, 'www.javbus.com')
  assert.deepEqual(out.headers[0].header, { Referer: 'https://www.javbus.com/', Cookie: 'a=1' })
})

test('serialize: empty headers rows are filtered out', () => {
  const state = {
    filterMode: 'none',
    sites: [],
    parses: [],
    headers: [
      { host: '', pairs: [{ name: '', value: '' }] },
      { host: 'a.com', pairs: [{ name: '', value: '' }] },
    ],
    lives: [],
    wallpaper: '',
    logo: '',
    flags: [],
    ads: [],
  }
  const out = serialize({}, state)
  assert.equal(out.headers.length, 1)
  assert.equal(out.headers[0].host, 'a.com')
})

test('buildLiveRows: parses config.lives into editor rows', () => {
  const rows = buildLiveRows({
    lives: [
      { name: '范明明', type: 0, url: 'https://example.com/live.m3u', playerType: 1, ua: 'okhttp/3.15', epg: 'http://epg.example.com', logo: 'http://logo.example.com/{name}.png' },
    ],
  })
  assert.equal(rows.length, 1)
  assert.equal(rows[0].name, '范明明')
  assert.equal(rows[0].type, 0)
  assert.equal(rows[0].url, 'https://example.com/live.m3u')
  assert.equal(rows[0].playerType, 1)
  assert.equal(rows[0].ua, 'okhttp/3.15')
})

test('buildLiveRows: empty/missing -> []', () => {
  assert.deepEqual(buildLiveRows({}), [])
  assert.deepEqual(buildLiveRows({ lives: [] }), [])
  assert.deepEqual(buildLiveRows({ lives: [null, 5] }), [])
})

test('serialize: lives are written to config', () => {
  const state = {
    filterMode: 'none',
    sites: [],
    parses: [],
    headers: [],
    lives: [
      { name: '范明明', type: 0, url: 'https://example.com/live.m3u', playerType: 1, ua: '', epg: '', logo: '' },
    ],
    wallpaper: '',
    logo: '',
    flags: [],
    ads: [],
  }
  const out = serialize({}, state)
  assert.equal(out.lives.length, 1)
  assert.equal(out.lives[0].name, '范明明')
  assert.equal(out.lives[0].type, 0)
  assert.equal(out.lives[0].url, 'https://example.com/live.m3u')
  assert.equal(out.lives[0].playerType, 1)
  assert.equal(out.lives[0].ua, undefined) // empty string filtered out
})

test('serialize: empty lives rows are filtered out', () => {
  const state = {
    filterMode: 'none',
    sites: [],
    parses: [],
    headers: [],
    lives: [
      { name: '', type: 0, url: '', playerType: 0, ua: '', epg: '', logo: '' },
    ],
    wallpaper: '',
    logo: '',
    flags: [],
    ads: [],
  }
  const out = serialize({}, state)
  assert.equal(out.lives, undefined)
})
