const base = '/api/plugin-maintenance'
let csrf: { token: string; headerName: string } | undefined

const setupErrors: Record<string, string> = {
  PAGE_DIAGNOSTICS_HOST_EVIDENCE_REQUIRED: '本次巡检缺少可匹配的宿主联网证据',
  PAGE_DIAGNOSTICS_OBSERVED_URL_REQUIRED: '尚未定位巡检访问的原页网址，请在诊断请求中指定已观测的网址',
  AUXILIARY_EVIDENCE_ALREADY_FROZEN: '这次巡检的诊断证据已保存，请查看原记录',
  UPSTREAM_AT_BASELINE_REQUIRED: '没有可确认的 AT 导入基线，请先指定共同基线',
  UPSTREAM_AT_BINDING_REQUIRED: '该来源尚未绑定 AT 插件',
  SOURCE_SETUP_DNS_NO_ADDRESS: '源站域名暂无可用的 IPv4 解析，尚未执行爬虫或启动修复',
  SOURCE_SETUP_DNS_NONPUBLIC: '源站域名解析到非公网地址，未建立连接或启动修复',
  SOURCE_SETUP_DNS_UNAVAILABLE: '源内地址未取得可用的公网解析，尚未执行爬虫或启动修复'
}

function authHeaders(): Record<string, string> {
  if (!window.location.pathname.startsWith('/plugin-maintenance/')) return {}
  const token = window.localStorage.getItem('token')
  return token ? { Authorization: token } : {}
}

function requireLogin(response: Response): void {
  if (response.status !== 401) return
  csrf = undefined
  window.location.assign(window.location.pathname.startsWith('/plugin-maintenance/') ? '/#/login' : '/login')
  throw new Error('请登录管理账户')
}

export async function request<T>(path: string, body?: unknown, method: 'POST' | 'PUT' = 'POST'): Promise<T> {
  const embedded = window.location.pathname.startsWith('/plugin-maintenance/')
  const writeToken = body === undefined ? undefined
    : embedded ? await request<{ token: string; headerName: string }>('/csrf')
    : (csrf ??= await request('/csrf'))
  const response = await fetch(base + path, {
    method: body === undefined ? 'GET' : method,
    credentials: 'same-origin',
    headers: {
      ...authHeaders(),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(writeToken ? { [writeToken.headerName]: writeToken.token } : {})
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  requireLogin(response)
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    if (response.status === 403) csrf = undefined
    throw new Error(setupErrors[error.code] ? `${setupErrors[error.code]} (${error.code})`
      : error.code || `请求失败 (${response.status})`)
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}

export async function sourceContent(id: string, sha: string): Promise<string> {
  const response = await fetch(`${base}/sources/${id}/artifacts/${sha}`, {
    credentials: 'same-origin', headers: authHeaders()
  })
  requireLogin(response)
  if (!response.ok) throw new Error(`源码读取失败 (${response.status})`)
  return response.text()
}

export async function downloadSource(id: string, sha: string): Promise<void> {
  const response = await fetch(`${base}/sources/${id}/export?sha=${encodeURIComponent(sha)}`, {
    credentials: 'same-origin', headers: authHeaders()
  })
  requireLogin(response)
  if (!response.ok) throw new Error(`源码包下载失败 (${response.status})`)
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url
  link.download = `source-${id}-${sha.slice(0, 12)}.zip`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
