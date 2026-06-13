// 订阅 override / 全局配置 的纯转换逻辑(无 Vue 依赖,便于单测)

export function parseOverride(text) {
  if (!text || !text.trim()) return {}
  try {
    const v = JSON.parse(text)
    return v && typeof v === 'object' && !Array.isArray(v) ? v : {}
  } catch {
    return null // null 表示非法 JSON
  }
}

export function stringify(config) {
  const keys = Object.keys(config || {})
  if (keys.length === 0) return ''
  return JSON.stringify(config)
}

// 返回 obj 中不在 modeledKeys 内的所有键 (raw 透传袋)
export function pickExtra(obj, modeledKeys) {
  const extra = {}
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return extra
  const set = new Set(modeledKeys)
  for (const k of Object.keys(obj)) {
    if (!set.has(k)) extra[k] = obj[k]
  }
  return extra
}

export function detectFilterMode(config) {
  const wl = config['sites-whitelist']
  if (Array.isArray(wl) && wl.length) return 'whitelist'
  const bl = config.blacklist
  const legacy = config['sites-blacklist']
  if ((Array.isArray(legacy) && legacy.length) || (bl && Array.isArray(bl.sites) && bl.sites.length)) return 'blacklist'
  return 'none'
}

export function disabledSiteKeys(config) {
  const set = new Set()
  if (Array.isArray(config['sites-blacklist'])) config['sites-blacklist'].forEach((k) => set.add(k))
  if (config.blacklist && Array.isArray(config.blacklist.sites)) config.blacklist.sites.forEach((k) => set.add(k))
  return [...set]
}

export function whitelistKeys(config) {
  return Array.isArray(config['sites-whitelist']) ? [...config['sites-whitelist']] : []
}

export function disabledParseNames(config) {
  return config.blacklist && Array.isArray(config.blacklist.parses) ? [...config.blacklist.parses] : []
}

// 返回 { key: {...all fields} } —— config.sites 里的完整 override
export function siteOverrideMap(config) {
  const map = {}
  if (Array.isArray(config.sites)) {
    for (const s of config.sites) {
      if (s && s.key != null) {
        const { key, ...override } = s
        map[String(key)] = override
      }
    }
  }
  return map
}

// 返回 config.sites 里 key ∉ catalogKeys 的项(自定义站点),原样
export function customSites(config, catalogKeys) {
  const set = new Set(catalogKeys)
  if (!Array.isArray(config.sites)) return []
  return config.sites.filter((s) => s && s.key != null && !set.has(String(s.key)))
}

const CUSTOM_SITE_KEYS = [
  'key', 'name', 'type', 'api', 'ext', 'jar', 'searchable', 'quickSearch',
  'filterable', 'changeable', 'indexs', 'timeout', 'order', 'style',
  'categories', 'header', 'playUrl', 'click',
]

function buildCustomSite(row) {
  const s = {}
  for (const k of CUSTOM_SITE_KEYS) {
    const v = row[k]
    if (v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0)) continue
    if (k === 'order') {
      const n = Number(v)
      if (Number.isFinite(n)) s[k] = n
      continue
    }
    s[k] = v
  }
  return s
}

export function buildHeaderRows(config) {
  if (!Array.isArray(config.headers)) return []
  return config.headers
    .filter((h) => h && typeof h === 'object')
    .map((h) => ({
      host: h.host || '',
      pairs: h.header && typeof h.header === 'object'
        ? Object.entries(h.header).map(([name, value]) => ({ name, value: String(value) }))
        : [],
    }))
}

function buildHeaderItem(row) {
  const header = {}
  for (const p of row.pairs || []) {
    if (p.name) header[p.name] = p.value || ''
  }
  if (!row.host && !Object.keys(header).length) return null
  const item = {}
  if (row.host) item.host = row.host
  if (Object.keys(header).length) item.header = header
  return item
}

// --- Live helpers ---

function normalizeGroup(g) {
  return {
    name: g.name || '',
    pass: g.pass ?? 0,
    channels: Array.isArray(g.channel) ? g.channel.map(normalizeChannel) : [],
  }
}

function normalizeChannel(c) {
  return {
    name: c.name || '',
    urls: Array.isArray(c.urls) ? [...c.urls] : [],
    number: c.number ?? '',
    logo: c.logo || '',
    epg: c.epg || '',
    ua: c.ua || '',
    format: c.format || '',
    origin: c.origin || '',
    referer: c.referer || '',
    tvgId: c.tvgId || '',
    tvgName: c.tvgName || '',
    parse: c.parse ?? 0,
    click: c.click || '',
    header: c.header && typeof c.header === 'object' ? { ...c.header } : {},
    catchup: c.catchup || null,
    drm: c.drm || null,
  }
}

export function buildLiveRows(config) {
  if (!Array.isArray(config.lives)) return []
  return config.lives
    .filter((l) => l && typeof l === 'object')
    .map((l) => ({
      name: l.name || '',
      type: l.type ?? 0,
      url: l.url || '',
      playerType: l.playerType ?? 0,
      ua: l.ua || '',
      epg: l.epg || '',
      logo: l.logo || '',
      api: l.api || '',
      ext: l.ext || '',
      jar: l.jar || '',
      click: l.click || '',
      origin: l.origin || '',
      referer: l.referer || '',
      timeZone: l.timeZone || '',
      timeout: l.timeout ?? '',
      header: l.header && typeof l.header === 'object' ? { ...l.header } : {},
      catchup: l.catchup || null,
      boot: l.boot ?? 0,
      pass: l.pass ?? 0,
      groups: Array.isArray(l.groups) ? l.groups.map(normalizeGroup) : [],
    }))
}

const LIVE_KEYS = [
  'name', 'type', 'url', 'playerType', 'ua', 'epg', 'logo',
  'api', 'ext', 'jar', 'click', 'origin', 'referer', 'timeZone',
  'timeout', 'header', 'catchup', 'boot', 'pass', 'groups',
]

const CHANNEL_KEYS = [
  'name', 'urls', 'number', 'logo', 'epg', 'ua', 'format',
  'origin', 'referer', 'tvgId', 'tvgName', 'parse', 'click', 'header', 'catchup', 'drm',
]

function buildChannelItem(c) {
  const item = {}
  for (const k of CHANNEL_KEYS) {
    if (k === 'header') {
      if (c.header && typeof c.header === 'object' && Object.keys(c.header).length) item.header = { ...c.header }
      continue
    }
    if (k === 'catchup' || k === 'drm') {
      if (c[k] && typeof c[k] === 'object' && Object.keys(c[k]).length) item[k] = { ...c[k] }
      continue
    }
    const v = c[k]
    if (v === undefined || v === null || v === '' || (Array.isArray(v) && !v.length)) continue
    item[k] = v
  }
  return Object.keys(item).length ? item : null
}

function buildGroupsArray(groups) {
  if (!Array.isArray(groups)) return []
  return groups.map((g) => {
    const item = {}
    if (g.name) item.name = g.name
    if (g.pass) item.pass = g.pass
    const channels = buildChannelsArray(g.channels)
    if (channels.length) item.channel = channels
    return Object.keys(item).length ? item : null
  }).filter(Boolean)
}

function buildChannelsArray(channels) {
  if (!Array.isArray(channels)) return []
  return channels.map(buildChannelItem).filter(Boolean)
}

function buildLiveItem(row) {
  if (!row.name && !row.url && !row.api) return null
  const item = {}
  for (const k of LIVE_KEYS) {
    if (k === 'groups') {
      const groups = buildGroupsArray(row.groups)
      if (groups.length) item.groups = groups
      continue
    }
    if (k === 'header') {
      if (row.header && typeof row.header === 'object' && Object.keys(row.header).length) {
        item.header = { ...row.header }
      }
      continue
    }
    if (k === 'catchup') {
      if (row.catchup && typeof row.catchup === 'object' && Object.keys(row.catchup).length) {
        item.catchup = { ...row.catchup }
      }
      continue
    }
    const v = row[k]
    if (v === undefined || v === null || v === '') continue
    item[k] = v
  }
  return Object.keys(item).length ? item : null
}

// --- Parse helpers ---

function buildCustomParse(row) {
  const p = { name: row.name }
  if (row.type != null && row.type !== '') p.type = Number(row.type)
  if (row.url) p.url = row.url
  const ext = {}
  if (Array.isArray(row.flag) && row.flag.length) ext.flag = row.flag
  if (row.header && Object.keys(row.header).length) ext.header = row.header
  if (Object.keys(ext).length) p.ext = ext
  return p
}

// --- Network builders ---

export function buildDohRows(config) {
  if (!Array.isArray(config.doh)) return []
  return config.doh
    .filter((d) => d && typeof d === 'object')
    .map((d) => ({
      name: d.name || '',
      url: d.url || '',
      ips: Array.isArray(d.ips) ? [...d.ips] : [],
    }))
}

export function buildProxyRows(config) {
  if (!Array.isArray(config.proxy)) return []
  return config.proxy
    .filter((p) => p && typeof p === 'object')
    .map((p) => ({
      name: p.name || '',
      hosts: Array.isArray(p.hosts) ? [...p.hosts] : [],
      urls: Array.isArray(p.urls) ? [...p.urls] : [],
    }))
}

export function buildRulesRows(config) {
  if (!Array.isArray(config.rules)) return []
  return config.rules
    .filter((r) => r && typeof r === 'object')
    .map((r) => ({
      name: r.name || '',
      hosts: Array.isArray(r.hosts) ? [...r.hosts] : [],
      regex: Array.isArray(r.regex) ? [...r.regex] : [],
      script: Array.isArray(r.script) ? [...r.script] : [],
      exclude: Array.isArray(r.exclude) ? [...r.exclude] : [],
    }))
}

// --- Config helpers ---

function setOrDelete(config, key, value) {
  if (value === undefined || value === null || value === '') delete config[key]
  else config[key] = value
}

function setArrOrDelete(config, key, value) {
  if (Array.isArray(value) && value.length) config[key] = value
  else delete config[key]
}

// 把编辑器状态写回 config(保留未建模键)
const ADVANCED_OVERRIDE_KEYS = [
  'ext', 'searchable', 'quickSearch', 'filterable', 'changeable',
  'style', 'timeout', 'indexs', 'playUrl', 'click', 'categories', 'header',
]

function buildAdvancedOverride(row) {
  const o = {}
  for (const k of ADVANCED_OVERRIDE_KEYS) {
    const v = row[k]
    if (v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0)) continue
    o[k] = v
  }
  return o
}

export function serialize(baseConfig, state) {
  const config = JSON.parse(JSON.stringify(baseConfig || {}))

  // sites: 上游/内置/插件局部 override + 自定义站点
  const sites = []
  for (const row of state.sites) {
    if (row.isCustom) {
      sites.push(buildCustomSite(row))
    } else {
      const o = {}
      if (row.name && row.name !== row.originalName) o.name = row.name
      else if (row.hadNameOverride && row.name) o.name = row.name
      if (row.order !== '' && row.order !== null && row.order !== undefined) o.order = Number(row.order)
      if (row.hasAdvancedOverride) Object.assign(o, buildAdvancedOverride(row))
      if (Object.keys(o).length) {
        o.key = row.key
        sites.push(o)
      }
    }
  }
  setArrOrDelete(config, 'sites', sites)

  // 过滤键:迁移旧 sites-blacklist,重建
  delete config['sites-blacklist']
  delete config['sites-whitelist']
  let blacklist =
    config.blacklist && typeof config.blacklist === 'object' && !Array.isArray(config.blacklist)
      ? config.blacklist
      : {}
  delete blacklist.sites

  if (state.filterMode === 'whitelist') {
    config['sites-whitelist'] = state.sites.filter((r) => r.enabled).map((r) => r.key)
  } else if (state.filterMode === 'blacklist') {
    const bl = state.sites.filter((r) => !r.enabled).map((r) => r.key)
    if (bl.length) blacklist.sites = bl
  }

  const disabledParses = state.parses.filter((p) => !p.enabled && !p.isCustom).map((p) => p.name)
  if (disabledParses.length) blacklist.parses = disabledParses
  else delete blacklist.parses

  if (Object.keys(blacklist).length) config.blacklist = blacklist
  else delete config.blacklist

  // 自定义解析
  const customParses = state.parses.filter((p) => p.isCustom).map(buildCustomParse)
  setArrOrDelete(config, 'parses', customParses)

  // 基础
  setOrDelete(config, 'wallpaper', state.wallpaper)
  setOrDelete(config, 'logo', state.logo)
  setOrDelete(config, 'notice', state.notice)
  setArrOrDelete(config, 'flags', state.flags)
  setArrOrDelete(config, 'ads', state.ads)

  // headers
  const headers = state.headers.map(buildHeaderItem).filter(Boolean)
  setArrOrDelete(config, 'headers', headers)

  // lives
  const lives = state.lives.map(buildLiveItem).filter(Boolean)
  setArrOrDelete(config, 'lives', lives)

  // doh
  const doh = state.doh.filter((d) => d.name || d.url).map((d) => {
    const item = {}
    if (d.name) item.name = d.name
    if (d.url) item.url = d.url
    if (Array.isArray(d.ips) && d.ips.length) item.ips = [...d.ips]
    return item
  })
  setArrOrDelete(config, 'doh', doh)

  // proxy
  const proxyItems = state.proxy.filter((p) => p.name || p.hosts.length || p.urls.length).map((p) => {
    const item = {}
    if (p.name) item.name = p.name
    if (p.hosts.length) item.hosts = [...p.hosts]
    if (p.urls.length) item.urls = [...p.urls]
    return item
  })
  setArrOrDelete(config, 'proxy', proxyItems)

  // rules
  const rules = state.rules.filter((r) => r.name || r.hosts.length || r.regex.length).map((r) => {
    const item = {}
    if (r.name) item.name = r.name
    if (r.hosts.length) item.hosts = [...r.hosts]
    if (r.regex.length) item.regex = [...r.regex]
    if (r.script.length) item.script = [...r.script]
    if (r.exclude.length) item.exclude = [...r.exclude]
    return item
  })
  setArrOrDelete(config, 'rules', rules)

  // hosts (string array)
  setArrOrDelete(config, 'hosts', state.hostsList)

  return config
}
