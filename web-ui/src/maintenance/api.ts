const base = '/api/plugin-maintenance'

const setupErrors: Record<string, string> = {
  REPAIR_ROUND_LIMIT_INVALID: '修复轮数必须为正整数',
  REPAIR_ROUND_LIMIT_BELOW_USED: '轮数上限不能小于已经使用的轮数',
  REPAIR_ROUND_LIMIT_CHANGED: '轮数上限已被更新，请刷新后再保存',
  REPAIR_ROUND_LIMIT_BUSY: '修复或模型调用正在进行，请停止或等待本轮结束后调整',
  REPAIR_ROUND_LIMIT_OVERFLOW: '轮数上限已达到整数存储上限',
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
  const token = window.localStorage.getItem('token')
  return token ? { Authorization: token } : {}
}

function requireLogin(response: Response): void {
  if (response.status !== 401) return
  window.localStorage.removeItem('token')
  window.location.assign(`${window.location.pathname}#/login`)
  throw new Error('请登录管理账户')
}

export async function request<T>(path: string, body?: unknown, method: 'POST' | 'PUT' = 'POST'): Promise<T> {
  const writeToken = body === undefined ? undefined
    : await request<{ token: string; headerName: string }>('/csrf')
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
    throw new Error(setupErrors[error.code] ? `${setupErrors[error.code]} (${error.code})`
      : error.code || `请求失败 (${response.status})`)
  }
  return response.status === 204 ? undefined as unknown as T : response.json() as Promise<T>
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
  await downloadFile(`/sources/${id}/export?sha=${encodeURIComponent(sha)}`, `source-${id}-${sha.slice(0, 12)}.zip`)
}

export async function downloadFile(path: string, filename: string): Promise<void> {
  const response = await fetch(base + path, {
    credentials: 'same-origin', headers: authHeaders()
  })
  requireLogin(response)
  if (!response.ok) throw new Error(`文件下载失败 (${response.status})`)
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1]
  const named = /filename="([^"]+)"/i.exec(disposition)?.[1]
  if (encoded) {
    try { filename = decodeURIComponent(encoded) } catch { /* Retain the caller's filename. */ }
  } else if (named) filename = named
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
