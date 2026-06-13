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
  pickExtra,
  buildHeaderRows,
  buildLiveRows,
  buildDohRows,
  buildProxyRows,
  buildRulesRows,
} from './subscriptionConfig.mjs'

const defaultState = () => ({
  filterMode: 'none',
  sites: [],
  parses: [],
  headers: [],
  lives: [],
  wallpaper: '',
  logo: '',
  notice: '',
  flags: [],
  ads: [],
  doh: [],
  proxy: [],
  rules: [],
  hostsList: [],
})

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
  const base = { 'sites-blacklist': ['x'], unknownKey: [{ name: 'keepme' }] }
  const state = {
    ...defaultState(),
    filterMode: 'blacklist',
    sites: [
      { key: 'a', origin: 'upstream', enabled: true, name: 'A', originalName: 'A', order: '', isCustom: false },
      { key: 'b', origin: 'builtin', enabled: false, name: 'B', originalName: 'B', order: '', isCustom: false },
    ],
    parses: [{ name: '虾米', enabled: false, isCustom: false }],
  }
  const out = serialize(base, state)
  assert.equal(out['sites-blacklist'], undefined)
  assert.deepEqual(out.blacklist.sites, ['b'])
  assert.deepEqual(out.blacklist.parses, ['虾米'])
  assert.deepEqual(out.unknownKey, [{ name: 'keepme' }]) // unknown key preserved
  assert.equal(out.sites, undefined) // no rename/order overrides
})

test('serialize: whitelist mode writes sites-whitelist of enabled keys', () => {
  const state = {
    ...defaultState(),
    filterMode: 'whitelist',
    sites: [
      { key: 'a', origin: 'upstream', enabled: true, name: 'A', originalName: 'A', order: '', isCustom: false },
      { key: 'b', origin: 'plugin', enabled: false, name: 'B', originalName: 'B', order: '', isCustom: false },
    ],
    parses: [],
  }
  const out = serialize({}, state)
  assert.deepEqual(out['sites-whitelist'], ['a'])
  assert.equal(out.blacklist, undefined)
})

test('serialize: upstream rename + order emit sites partial; custom site full', () => {
  const state = {
    ...defaultState(),
    sites: [
      { key: 'a', origin: 'upstream', enabled: true, name: '新名', originalName: 'A', order: 50, isCustom: false },
      { key: 'mine', origin: 'custom', enabled: true, isCustom: true, name: '自定义', type: 3, api: 'csp_X', searchable: 1, quickSearch: 1, filterable: 1 },
    ],
    parses: [],
    wallpaper: 'http://w',
    flags: ['x'],
  }
  const out = serialize({}, state)
  assert.deepEqual(out.sites.find((s) => s.key === 'a'), { key: 'a', name: '新名', order: 50 })
  const custom = out.sites.find((s) => s.key === 'mine')
  assert.equal(custom.type, 3)
  assert.equal(custom.api, 'csp_X')
  assert.equal(out.wallpaper, 'http://w')
  assert.deepEqual(out.flags, ['x'])
})

test('serialize: custom site order is written as number', () => {
  const state = {
    ...defaultState(),
    sites: [
      { key: 'mine', origin: 'custom', enabled: true, isCustom: true, name: '自定义', type: 3, api: 'csp_X', order: '12' },
    ],
  }
  const out = serialize({}, state)
  assert.equal(out.sites[0].order, 12)
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
    ...defaultState(),
    headers: [
      { host: 'www.javbus.com', pairs: [
        { name: 'Referer', value: 'https://www.javbus.com/' },
        { name: 'Cookie', value: 'a=1' },
      ] },
    ],
  }
  const out = serialize({}, state)
  assert.equal(out.headers.length, 1)
  assert.equal(out.headers[0].host, 'www.javbus.com')
  assert.deepEqual(out.headers[0].header, { Referer: 'https://www.javbus.com/', Cookie: 'a=1' })
})

test('serialize: empty headers rows are filtered out', () => {
  const state = {
    ...defaultState(),
    headers: [
      { host: '', pairs: [{ name: '', value: '' }] },
      { host: 'a.com', pairs: [{ name: '', value: '' }] },
    ],
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

test('buildLiveRows: preserves extended fields', () => {
  const rows = buildLiveRows({
    lives: [{
      name: '台湾频道', url: './live.json', api: 'csp_Live', ext: 'data',
      jar: './spider.jar', click: 'http://x', origin: 'http://o', referer: 'http://r',
      timeZone: 'Asia/Taipei', timeout: 30, boot: 1, pass: 1,
      header: { 'User-Agent': 'test' },
      catchup: { type: 'append', source: '?t={utc}' },
      groups: [{ name: '新闻', pass: 1, channel: [{ name: 'TVBS', urls: ['http://tvbs.m3u8'], number: '56' }] }],
    }],
  })
  assert.equal(rows[0].api, 'csp_Live')
  assert.equal(rows[0].ext, 'data')
  assert.equal(rows[0].jar, './spider.jar')
  assert.equal(rows[0].timeZone, 'Asia/Taipei')
  assert.equal(rows[0].timeout, 30)
  assert.equal(rows[0].boot, 1)
  assert.equal(rows[0].pass, 1)
  assert.deepEqual(rows[0].header, { 'User-Agent': 'test' })
  assert.deepEqual(rows[0].catchup, { type: 'append', source: '?t={utc}' })
  assert.equal(rows[0].groups.length, 1)
  assert.equal(rows[0].groups[0].name, '新闻')
  assert.equal(rows[0].groups[0].channels.length, 1)
  assert.equal(rows[0].groups[0].channels[0].name, 'TVBS')
})

test('serialize: lives are written to config', () => {
  const state = {
    ...defaultState(),
    lives: [
      { name: '范明明', type: 0, url: 'https://example.com/live.m3u', playerType: 1, ua: '', epg: '', logo: '' },
    ],
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
    ...defaultState(),
    lives: [
      { name: '', type: 0, url: '', playerType: 0, ua: '', epg: '', logo: '' },
    ],
  }
  const out = serialize({}, state)
  assert.equal(out.lives, undefined)
})

test('serialize: lives with groups/channels round-trip', () => {
  const state = {
    ...defaultState(),
    lives: [{
      name: '直播', url: './live.json',
      groups: [{
        name: '新闻',
        channels: [
          { name: 'TVBS', urls: ['http://tvbs.m3u8'], number: '56' },
          { name: '民视', urls: ['http://ftv1.m3u8', 'http://ftv2.m3u8'], logo: 'http://logo.png' },
        ],
      }],
    }],
  }
  const out = serialize({}, state)
  assert.equal(out.lives[0].groups[0].channel.length, 2)
  assert.equal(out.lives[0].groups[0].channel[0].name, 'TVBS')
  assert.deepEqual(out.lives[0].groups[0].channel[0].urls, ['http://tvbs.m3u8'])
  assert.equal(out.lives[0].groups[0].channel[1].name, '民视')
  assert.equal(out.lives[0].groups[0].channel[1].logo, 'http://logo.png')
})

test('serialize: notice is written to config', () => {
  const state = { ...defaultState(), notice: '欢迎使用' }
  const out = serialize({}, state)
  assert.equal(out.notice, '欢迎使用')
})

test('serialize: empty notice is deleted', () => {
  const state = { ...defaultState() }
  const out = serialize({ notice: 'old' }, state)
  assert.equal(out.notice, undefined)
})

test('buildDohRows: parses config.doh into editor rows', () => {
  const rows = buildDohRows({
    doh: [
      { name: 'Google', url: 'https://dns.google/dns-query', ips: ['8.8.4.4', '8.8.8.8'] },
      { name: 'Cloudflare', url: 'https://cloudflare-dns.com/dns-query', ips: [] },
    ],
  })
  assert.equal(rows.length, 2)
  assert.equal(rows[0].name, 'Google')
  assert.deepEqual(rows[0].ips, ['8.8.4.4', '8.8.8.8'])
  assert.equal(rows[1].name, 'Cloudflare')
})

test('buildDohRows: empty/missing -> []', () => {
  assert.deepEqual(buildDohRows({}), [])
  assert.deepEqual(buildDohRows({ doh: [] }), [])
  assert.deepEqual(buildDohRows({ doh: [null, 5] }), [])
})

test('buildProxyRows: parses config.proxy into editor rows', () => {
  const rows = buildProxyRows({
    proxy: [
      { name: '指定代理', hosts: ['googlevideo.com'], urls: ['http://127.0.0.1:7890'] },
    ],
  })
  assert.equal(rows.length, 1)
  assert.deepEqual(rows[0].hosts, ['googlevideo.com'])
  assert.deepEqual(rows[0].urls, ['http://127.0.0.1:7890'])
})

test('buildProxyRows: empty/missing -> []', () => {
  assert.deepEqual(buildProxyRows({}), [])
  assert.deepEqual(buildProxyRows({ proxy: [null] }), [])
})

test('buildRulesRows: parses config.rules into editor rows', () => {
  const rows = buildRulesRows({
    rules: [
      { hosts: ['video.example.com'], regex: ['m3u8?token='], exclude: ['preview.json'] },
    ],
  })
  assert.equal(rows.length, 1)
  assert.deepEqual(rows[0].hosts, ['video.example.com'])
  assert.deepEqual(rows[0].regex, ['m3u8?token='])
  assert.deepEqual(rows[0].exclude, ['preview.json'])
  assert.deepEqual(rows[0].script, [])
})

test('buildRulesRows: empty/missing -> []', () => {
  assert.deepEqual(buildRulesRows({}), [])
  assert.deepEqual(buildRulesRows({ rules: [null] }), [])
})

test('serialize: doh/proxy/rules/hosts are written to config', () => {
  const state = {
    ...defaultState(),
    doh: [{ name: 'Google', url: 'https://dns.google/dns-query', ips: ['8.8.4.4'] }],
    proxy: [{ name: '代理', hosts: ['x.com'], urls: ['http://127.0.0.1:7890'] }],
    rules: [{ name: '规则', hosts: ['y.com'], regex: ['m3u8'], script: [], exclude: [] }],
    hostsList: ['old.cdn.example.com=new.cdn.example.com'],
  }
  const out = serialize({}, state)
  assert.equal(out.doh[0].name, 'Google')
  assert.equal(out.proxy[0].name, '代理')
  assert.equal(out.rules[0].name, '规则')
  assert.deepEqual(out.hosts, ['old.cdn.example.com=new.cdn.example.com'])
})

test('serialize: empty doh/proxy/rules/hosts are deleted', () => {
  const state = defaultState()
  const out = serialize({ doh: [{ name: 'old' }], proxy: [], rules: [], hosts: ['a=b'] }, state)
  assert.equal(out.doh, undefined)
  assert.equal(out.proxy, undefined)
  assert.equal(out.rules, undefined)
  assert.equal(out.hosts, undefined)
})

test('pickExtra: returns unmodeled keys only', () => {
  assert.deepEqual(pickExtra({ a: 1, b: 2, c: 3 }, ['a', 'c']), { b: 2 })
  assert.deepEqual(pickExtra({}, ['a']), {})
  assert.deepEqual(pickExtra(null, ['a']), {})
  assert.deepEqual(pickExtra([1, 2], ['a']), {})
})
