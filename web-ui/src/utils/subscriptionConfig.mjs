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

// 返回 { key: {name?, order?} } —— config.sites 里的局部 override
export function siteOverrideMap(config) {
  const map = {}
  if (Array.isArray(config.sites)) {
    for (const s of config.sites) {
      if (s && s.key != null) {
        const o = {}
        if (s.name != null) o.name = s.name
        if (s.order != null) o.order = s.order
        map[String(s.key)] = o
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
    s[k] = v
  }
  return s
}

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

function setOrDelete(config, key, value) {
  if (value === undefined || value === null || value === '') delete config[key]
  else config[key] = value
}

function setArrOrDelete(config, key, value) {
  if (Array.isArray(value) && value.length) config[key] = value
  else delete config[key]
}

// 把编辑器状态写回 config(保留未建模键)
export function serialize(baseConfig, state) {
  const config = JSON.parse(JSON.stringify(baseConfig || {}))

  // sites: 上游局部 override + 自定义站点
  const sites = []
  for (const row of state.sites) {
    if (row.isCustom) {
      sites.push(buildCustomSite(row))
    } else if (row.origin === 'upstream') {
      const o = {}
      if (row.name && row.name !== row.originalName) o.name = row.name
      else if (row.hadNameOverride && row.name) o.name = row.name
      if (row.order !== '' && row.order !== null && row.order !== undefined) o.order = Number(row.order)
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
  setOrDelete(config, 'wall', state.wall)
  setOrDelete(config, 'logo', state.logo)
  setArrOrDelete(config, 'flags', state.flags)
  setArrOrDelete(config, 'ads', state.ads)

  return config
}
