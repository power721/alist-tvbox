export const countKeys = ['homeClasses', 'homeItems', 'homeCovers', 'categoryItems', 'categoryCovers',
  'page2Items', 'detailItems', 'detailCovers', 'playGroups', 'playEpisodes',
  'fixtureChecks', 'compatChecks', 'dualRuntimeChecks'] as const
export type CountKey = typeof countKeys[number]
export interface BusinessHttp {
  page: 'home' | 'category' | 'detail'; responseStatus: number; responseBytes: number
  responseSha256: string; elapsedMilliseconds: number
}
export interface BusinessSample {
  sourceSha256: string; imageId: string; runtimeSha256: string; receiptSha256: string; jobId: string
  containerId: string; observedAt: string; status: string; counts: Record<CountKey, number | null>
  fixturePassed: boolean; deniedCodes: string[]; http: BusinessHttp[]
  limits: { maxResponseBytes: number; maxTotalResponseBytes: number; maxRequests: number }
  controls: { network: 'none'; uid: 65532; cleanupConfirmed: true; containerAbsent: true }
}
export interface BusinessComparison {
  schemaVersion: 1; kind: 'nas-t4-business-comparison'; evidenceClass: 'untrusted_source_output'
  baseline: BusinessSample; candidate: BusinessSample
}
const digest = /^[a-f0-9]{64}$/
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const denied = new Set(['EGRESS_BYTE_BUDGET', 'EGRESS_REQUEST_BUDGET', 'EGRESS_URL_DENIED',
  'EGRESS_HTTP_FAILED', 'EGRESS_REDIRECT_DENIED', 'EGRESS_HTTP_DEADLINE', 'EGRESS_HEADERS_DENIED',
  'EGRESS_ACCOUNT_TOKEN_DENIED', 'EGRESS_HEADER_ORIGIN_DENIED', 'EGRESS_METHOD_DENIED',
  'EGRESS_BODY_DENIED', 'EGRESS_CONTENT_ENCODING_DENIED'])
function require(value: unknown): asserts value {
  if (!value) throw new Error('业务快照格式或边界无效')
}
function object(value: unknown, keys: readonly string[]): Record<string, any> {
  require(value !== null && typeof value === 'object' && !Array.isArray(value))
  const result = value as Record<string, unknown>
  require(Object.keys(result).length === keys.length && keys.every(key => Object.hasOwn(result, key)))
  return result
}
function integer(value: unknown, maximum: number, minimum = 0) {
  require(typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum)
}
function sample(input: unknown): BusinessSample {
  const value = object(input, ['sourceSha256', 'imageId', 'runtimeSha256', 'receiptSha256', 'jobId',
    'containerId', 'observedAt', 'status', 'counts', 'fixturePassed', 'deniedCodes', 'http', 'limits', 'controls'])
  for (const key of ['sourceSha256', 'runtimeSha256', 'receiptSha256', 'containerId']) {
    require(typeof value[key] === 'string' && digest.test(value[key]))
  }
  require(typeof value.imageId === 'string' && /^sha256:[a-f0-9]{64}$/.test(value.imageId))
  require(typeof value.jobId === 'string' && uuid.test(value.jobId))
  require(typeof value.observedAt === 'string' && value.observedAt.length <= 32
    && /^\d{4}-\d\d-\d\dT/.test(value.observedAt) && Number.isFinite(Date.parse(value.observedAt)))
  require(['home_empty', 'init_returned', 'category_returned', 'detail_returned', 'source_exception']
    .includes(value.status))
  const counts = object(value.counts, countKeys)
  for (const key of countKeys) if (counts[key] !== null) integer(counts[key], 10000)
  require(typeof value.fixturePassed === 'boolean')
  require(Array.isArray(value.deniedCodes) && value.deniedCodes.length <= 24
    && value.deniedCodes.every((code: unknown) => typeof code === 'string' && denied.has(code)))
  require(Array.isArray(value.http) && value.http.length <= 24)
  for (const row of value.http) {
    const http = object(row, ['page', 'responseStatus', 'responseBytes', 'responseSha256', 'elapsedMilliseconds'])
    require(['home', 'category', 'detail'].includes(http.page))
    integer(http.responseStatus, 599, 100)
    integer(http.responseBytes, 2097152)
    integer(http.elapsedMilliseconds, 120000)
    require(typeof http.responseSha256 === 'string' && digest.test(http.responseSha256))
  }
  const limits = object(value.limits, ['maxResponseBytes', 'maxTotalResponseBytes', 'maxRequests'])
  integer(limits.maxResponseBytes, 2097152, 1)
  integer(limits.maxTotalResponseBytes, 4194304, 1)
  integer(limits.maxRequests, 24, 1)
  require(value.http.length <= limits.maxRequests
    && value.http.every((row: BusinessHttp) => row.responseBytes <= limits.maxResponseBytes)
    && value.http.reduce((sum: number, row: BusinessHttp) => sum + row.responseBytes, 0) <= limits.maxTotalResponseBytes)
  const controls = object(value.controls, ['network', 'uid', 'cleanupConfirmed', 'containerAbsent'])
  require(controls.network === 'none' && controls.uid === 65532
    && controls.cleanupConfirmed === true && controls.containerAbsent === true)
  return value as unknown as BusinessSample
}
export function validateBusinessComparison(input: unknown): BusinessComparison {
  const value = object(input, ['schemaVersion', 'kind', 'evidenceClass', 'baseline', 'candidate'])
  require(value.schemaVersion === 1 && value.kind === 'nas-t4-business-comparison'
    && value.evidenceClass === 'untrusted_source_output')
  sample(value.baseline)
  sample(value.candidate)
  return value as unknown as BusinessComparison
}
export async function readBusinessComparison(file: Blob) {
  require(file.size > 0 && file.size <= 131072)
  const body = await file.arrayBuffer()
  require(body.byteLength === file.size)
  let value: unknown
  try { value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body)) }
  catch { throw new Error('业务快照不是有效 UTF-8 JSON') }
  const comparison = validateBusinessComparison(value)
  const sha = await crypto.subtle.digest('SHA-256', body)
  return { comparison, fileSha256: Array.from(new Uint8Array(sha), b => b.toString(16).padStart(2, '0')).join('') }
}
