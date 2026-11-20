<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { Aim } from '@element-plus/icons-vue'
import { request } from './api'

const props = defineProps<{ runId: string; artifactSha256: string; repairId?: string; disabled?: boolean }>()
interface ObservedCall { id: number; method: string; inputSha256: string }
interface Diagnostic {
  artifactSha256: string; freshness: string; loaderReceiptMatched: boolean
  candidateId?: string; latestCandidateEvidenceUnavailable?: boolean
  hostFailureDiagnosticMatched?: boolean
  http: { completedResponses: number; statusCounts: Record<string, number> }
  transportEvidence?: { scope?: string; observationsDropped: number; observations: { requestNumber: number; addressIndex: number
    stage: string; kind: string; elapsedMilliseconds: number; observedDuringCall?: ObservedCall }[] }
  cooldownEvidence?: { observations: { responseStatus: number; retryAfter: { kind: string; seconds: string | null }
    observedDuringCall?: ObservedCall; observedAtEpochMilliseconds?: number
    cooldownContext?: { recordedAtEpochMilliseconds: number; remainingWaitMilliseconds: string | null
      taskBudgetRemainingMilliseconds: number | null; fitsTaskBudget: boolean | null } }[] }
  dnsEvidence?: { scope?: string; observations: { dnsStatus: number | null; kind: string; answerCount: number
    publicIpv4Count: number; recordType?: string; publicIpv6Count?: number; resolver?: string; fallbackFrom?: string
    elapsedMilliseconds?: number; cacheHit?: boolean; observedDuringCall?: ObservedCall }[] }
  findings: { node: string; status: string; reason: string; route: string; failureType?: string
    executionPhase?: string; networkDenials?: { denialIndex: number; method: string; code: string }[]
    httpEvidence?: { calls: { id: number; method: string; statusCounts: Record<string, number> }[] }
    regression?: { field: string; before: number; after: number }
    contentRegression?: { categoryIdSha256?: string; before: number; after: number; detailComparable?: boolean }
    observedDuringCall?: ObservedCall; matchedResourceEvidenceAvailable?: boolean
    playerValueEvidence?: { truncated: boolean; matches: { path: (string | number)[]; embeddedPath?: (string | number)[]
      observedDuringCall: ObservedCall; playerValueMatches: Record<string, string> }[] }
    accessClassification?: string; accessAssociation?: string; requestDiagnostic?: { reason: string; headerName: string }
    contractObservations?: { identityEvidence?: Record<string, boolean>; firstItems?: number; secondItems?: number
      pagesObserved?: number; playerRouteKind?: string; offlineResolverRequired?: boolean
      playerContract?: { detailCallId?: number; playCallId?: number; returnedUrlShape?: string
        returnedUrlMatchesIdentifier?: boolean; parseMode?: string; hasWhitespace?: boolean; hasFragment?: boolean } } }[]
  accessPage?: { classification: string; targetNode: string; observedNode?: string
    observedDuringCall?: { id: number; method: string }; responseStatus?: number }
  routes: string[]; missingMaterials: string[]; resourceEvidenceAvailable: boolean
}
const report = ref<Diagnostic>()
const busy = ref(false)
const error = ref('')
let revision = 0
const routes: Record<string, string> = {
  request_budget: '请求数量或正文预算：核对重复请求、跳转和分页',
  cooldown: '站点限流：核对冷却时间',
  network: '网络或上游故障：核对连接与 HTTP 响应',
  request_policy: '请求适配：定位被拒请求与来源',
  dependency: '运行依赖：核对导入与隔离环境',
  initialization: '初始化：定位首个失败动作',
  runtime_configuration: 'AT 宿主解析未配置：补齐宿主环境后复验原候选',
  player_resource: '外部解析：补齐实际播放页证据',
  at_offline: 'AT 离线资源：保留磁力或网盘交接',
  media_resource: '媒体资源：核对回调、类型与字节',
  credentials: '站点认证：补充或更新凭据',
  access_evidence: '访问受限：区分登录、拒绝和挑战页面',
  runtime_evidence: '运行证据不足',
  source_regression: '候选内容减少：核对真实选集与误入链接，保留已修复能力',
  source_contract: '源码返回异常：追踪输入、提取与变换'
}
const materials: Record<string, string> = {
  matched_loader_receipt: '匹配当前输入的装载回执',
  completed_http_response: '完整的联网响应',
  bound_resource_evidence: '可关联到本次请求的资源证据',
  public_response_structure: '页面或接口结构证据',
  current_input_receipt: '当前版本的运行报告',
  player_value_correspondence: '选集或返回地址与接口字段的匹配证据',
  action_http_association: '错误响应与具体动作的关联证据',
  content_regression_action_resources: '内容减少的分类或详情调用所对应的页面、接口证据'
}
function findingLabel(finding: Diagnostic['findings'][number]) {
  if (finding.contentRegression) {
    const names: Record<string, string> = { DECLARED_CATEGORY_LOSS: '分类数量', CATEGORY_BECAME_EMPTY: '分类条目',
      CATEGORY_ITEMS_COLLAPSED: '分类条目', DETAIL_EPISODES_COLLAPSED: '同作品选集' }
    const { before, after } = finding.contentRegression
    return `${names[finding.reason] || '内容'}减少：${before} → ${after}${callIntervalLabel(finding.observedDuringCall)}（原因待核验）`
  }
  if (['RUNTIME_REGRESSION', 'RUNTIME_NETWORK_UNAVAILABLE'].includes(finding.reason) && finding.regression) {
    const names: Record<string, string> = { homeClasses: '首页分类', homeItems: '首页条目', homeCovers: '首页封面',
      categoryItems: '分类条目', categoryCovers: '分类封面', page2Items: '第二页条目', detailItems: '详情条目',
      detailCovers: '详情封面', playGroups: '播放线路', playEpisodes: '选集条目' }
    const { field, before, after } = finding.regression
    if (finding.reason === 'RUNTIME_NETWORK_UNAVAILABLE')
      return `${names[field] || field}待复验：${before} → ${after}（同动作出现网络失败，先复测现有候选，因果待确认）`
    return `${names[field] || field}退化：${before} → ${after}（采样输出，原因待确认）`
  }
  if (finding.reason === 'EGRESS_HEADERS_DENIED' && finding.requestDiagnostic?.reason === 'header_not_allowed')
    return `请求头未适配：${finding.requestDiagnostic.headerName}`
  if (finding.accessClassification === 'cloudflare_access_denied')
    return `观察到 Cloudflare 明确拒绝页，不是等待验证的挑战页（原始结果：${finding.reason}）`
  return ['javascript_cookie_challenge', 'cloudflare_challenge'].includes(finding.accessClassification || '')
    ? `观察到访问挑战页，故障因果未确认（原始结果：${finding.reason}）` : finding.reason
}
function httpLabel(finding: Diagnostic['findings'][number]) {
  const methods: Record<string, string> = { home: '首页', category: '分类', search: '搜索', detail: '详情', play: '取链' }
  return finding.httpEvidence?.calls.map(call => `${methods[call.method] || call.method} #${call.id} · ${
    Object.entries(call.statusCounts).map(([status, count]) => `HTTP ${status} × ${count}`).join('、')
  }`).join('；') || ''
}
function transportLabel(row: NonNullable<Diagnostic['transportEvidence']>['observations'][number]) {
  const stages: Record<string, string> = { tcp_connect: 'TCP 连接', tls_handshake: 'TLS 握手',
    request_send: '发送请求', response_headers: '接收响应头', response_body: '接收正文' }
  const kinds: Record<string, string> = { certificate: '证书错误', timeout: '超时', tls: 'TLS 错误',
    connection_refused: '连接被拒绝', connection_reset: '连接被重置', http_protocol: 'HTTP 协议错误',
    invalid_response: '响应无效', io: '网络读写错误' }
  return `请求 #${row.requestNumber} · 地址尝试 ${row.addressIndex + 1} · ${stages[row.stage] || row.stage} · ${
    kinds[row.kind] || row.kind} · ${row.elapsedMilliseconds} 毫秒${callIntervalLabel(row.observedDuringCall)}`
}
function callIntervalLabel(call?: ObservedCall) {
  if (!call) return ''
  const methods: Record<string, string> = { init: '初始化', home: '首页', category: '分类', search: '搜索', detail: '详情', play: '取链' }
  return ` · ${methods[call.method] || call.method} #${call.id} 调用期间`
}
function cooldownLabel(row: NonNullable<Diagnostic['cooldownEvidence']>['observations'][number]) {
  const timing = row.retryAfter
  const label = timing.kind === 'absent' ? 'HTTP 429 · 未提供 Retry-After，等待时长未知'
    : timing.kind === 'invalid' ? 'HTTP 429 · Retry-After 无效，等待时长未知'
    : `HTTP 429 · ${timing.kind === 'date' ? '日期型' : '秒数型'} Retry-After · 响应当时 ${timing.seconds} 秒`
  const context = row.cooldownContext
  const parts = [label + callIntervalLabel(row.observedDuringCall)]
  if (context) {
    parts.push(`采样时间 ${new Date(context.recordedAtEpochMilliseconds).toLocaleString()}`)
    if (context.remainingWaitMilliseconds !== null) parts.push(`当时剩余等待 ${context.remainingWaitMilliseconds} 毫秒`)
    if (context.taskBudgetRemainingMilliseconds !== null) parts.push(`当时任务剩余 ${context.taskBudgetRemainingMilliseconds} 毫秒`)
    if (context.fitsTaskBudget !== null) parts.push(context.fitsTaskBudget ? '当时预算可容纳等待' : '当时预算不足以容纳等待')
  }
  return parts.join(' · ')
}
function dnsLabel(row: NonNullable<Diagnostic['dnsEvidence']>['observations'][number]) {
  const kinds: Record<string, string> = { success: '获得公网地址', no_address: '未获得地址', resolver_error: '解析服务错误',
    nonpublic_address: '返回了不允许访问的地址', invalid_response: '解析响应无效', transport_error: '解析服务连接失败' }
  const parts = [kinds[row.kind] || row.kind, row.dnsStatus === null ? 'DNS 状态未记录' : `DNS 状态 ${row.dnsStatus}`,
    `地址记录 ${row.answerCount} 条`, `公网 IPv4 ${row.publicIpv4Count} 个`]
  if (row.recordType === 'AAAA' && typeof row.publicIpv6Count === 'number') parts.push(`公网 IPv6 ${row.publicIpv6Count} 个`)
  if (row.resolver) parts.push(`${row.resolver}${row.recordType ? ` ${row.recordType}` : ''}`)
  if (row.fallbackFrom) parts.push('备用解析')
  if (row.cacheHit) parts.push('复用失败缓存，未重新解析')
  else if (typeof row.elapsedMilliseconds === 'number') parts.push(`本阶段 ${row.elapsedMilliseconds} 毫秒`)
  return parts.join(' · ') + callIntervalLabel(row.observedDuringCall)
}
function networkDenialLabel(finding: Diagnostic['findings'][number]) {
  const methods: Record<string, string> = { home: '首页', category: '分类', search: '搜索', detail: '详情', play: '取链' }
  const rows = finding.networkDenials?.map(row => `${methods[row.method] || row.method} · 拒绝记录 #${row.denialIndex + 1} · ${row.code}`)
  return rows?.length ? `${rows.join('；')}（方法级观察，尚未关联到某次连接尝试）` : ''
}
function phaseLabel(finding: Diagnostic['findings'][number]) {
  const phases: Record<string, string> = { ready: '运行器就绪', source: '源码传入', frame: '等待通信',
    loader: '装载调用', fetch: '出站请求', exit: '等待执行结束' }
  return finding.executionPhase ? `执行停止阶段：${phases[finding.executionPhase] || finding.executionPhase}` : ''
}
function contractLabel(finding: Diagnostic['findings'][number]) {
  const observed = finding.contractObservations
  if (!observed) return ''
  const parts: string[] = []
  const names: Record<string, string> = { exactMatch: '详情 ID', guidCoverMatch: 'GUID 与封面',
    atvpDetailPrefixMatch: 'AT 详情前缀', nameMatch: '名称', coverMatch: '封面' }
  for (const [key, name] of Object.entries(names)) {
    const matched = observed.identityEvidence?.[key]
    if (typeof matched === 'boolean') parts.push(`${name}${matched ? '匹配' : '不匹配'}`)
  }
  if (typeof observed.firstItems === 'number') parts.push(`首页 ${observed.firstItems} 条`)
  if (typeof observed.secondItems === 'number') parts.push(`第二页 ${observed.secondItems} 条`)
  if (typeof observed.pagesObserved === 'number') parts.push(`已观察 ${observed.pagesObserved} 页`)
  if (observed.playerRouteKind === 'magnet') parts.push('磁力交接')
  if (observed.offlineResolverRequired === true) parts.push('需要 AT 离线解析')
  const player = observed.playerContract
  if (player) {
    const shapes: Record<string, string> = { not_string: '非字符串', empty: '空地址', absolute_http: '完整 HTTP 地址',
      magnet: '磁力链接', protocol_relative: '缺少协议的地址', relative_path: '相对路径', json_like: 'JSON 形态',
      html_like: 'HTML 形态', percent_encoded_url: '百分号编码地址', base64_encoded_url: 'Base64 编码地址',
      custom_scheme: '自定义协议', opaque: '不透明标识', malformed: '地址格式异常' }
    if (player.playCallId) parts.push(`取链调用 #${player.playCallId}`)
    if (player.returnedUrlShape && shapes[player.returnedUrlShape]) parts.push(`返回值：${shapes[player.returnedUrlShape]}`)
    if (player.returnedUrlMatchesIdentifier === true) parts.push('原样返回选集标识')
    if (player.parseMode === 'parse_required') parts.push('要求外部解析')
    if (player.hasWhitespace === true) parts.push('含空白或控制字符')
    if (player.hasFragment === true) parts.push('含地址片段')
  }
  const roles: Record<string, string> = { selectedIdentifier: '选集标识', returnedUrl: '返回地址' }
  const conversions: Record<string, string> = { exact: '原值相同', percent_decode: '百分号解码后相同',
    base64_decode: 'Base64 解码后相同' }
  const pathLabel = (path: (string | number)[]) => '$' + path.map(key =>
    typeof key === 'number' ? `[${key}]` : `.${key}`).join('')
  for (const match of finding.playerValueEvidence?.matches || []) {
    const field = pathLabel(match.path) + (match.embeddedPath ? ` → JSON${pathLabel(match.embeddedPath)}` : '')
    const matches = Object.entries(match.playerValueMatches).filter(([role, conversion]) => roles[role] && conversions[conversion])
      .map(([role, conversion]) => `${roles[role]}${conversions[conversion]}`).join('、')
    if (matches) parts.push(`调用 #${match.observedDuringCall.id} 响应字段 ${field}：${matches}（非因果证明）`)
  }
  if (finding.playerValueEvidence?.truncated) parts.push('响应字段采样不完整')
  return parts.join('；')
}
function accessPageLabel(page: NonNullable<Diagnostic['accessPage']>) {
  const names: Record<string, string> = { cloudflare_challenge: 'Cloudflare 挑战页',
    javascript_cookie_challenge: 'JavaScript Cookie 挑战页', cloudflare_access_denied: 'Cloudflare 明确拒绝页' }
  const name = names[page.classification] || '访问受限页'
  const methods: Record<string, string> = { init: '初始化', home: '首页', category: '分类', search: '搜索', detail: '详情', play: '取链' }
  const call = page.observedDuringCall
  const scope = call ? `${methods[call.method] || call.method}调用 #${call.id} 期间${page.observedNode ? `（${page.observedNode}）` : ''}`
    : '动作未记录'
  return `已观察到 ${name}${page.responseStatus ? `（HTTP ${page.responseStatus}）` : ''}；${scope}；诊断目标 ${page.targetNode}。尚不能确认是故障原因。`
}
async function diagnose() {
  if (busy.value || props.disabled || !props.runId) return
  const version = ++revision
  busy.value = true
  error.value = ''
  try {
    const path = props.repairId ? `/repairs/${props.repairId}/diagnostics` : `/runs/${props.runId}/diagnostics`
    const value = await request<Diagnostic>(path)
    if (version === revision) report.value = value
  } catch (reason) {
    if (version === revision) error.value = reason instanceof Error ? reason.message : '加强诊断失败'
  } finally { if (version === revision) busy.value = false }
}
watch(() => [props.runId, props.artifactSha256, props.repairId], () => {
  revision++
  report.value = undefined
  error.value = ''
  busy.value = false
}, { flush: 'sync' })
onUnmounted(() => { revision++ })
</script>

<template>
  <section class="enhanced-diagnostics" aria-label="加强诊断">
    <el-button :icon="Aim" :loading="busy" :disabled="busy || disabled || !runId" @click="diagnose">加强诊断</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="report">
      <p v-if="report.freshness !== 'current'" role="status">历史输入，需更新运行报告。</p>
      <p>完整响应 {{ report.http.completedResponses }} 次 · {{ report.candidateId ? '最近候选' : '原件' }}
        <span v-if="report.resourceEvidenceAvailable"> · 已有页面结构证据</span></p>
      <p v-if="report.accessPage" role="status">{{ accessPageLabel(report.accessPage) }}</p>
      <ul v-if="report.routes.length"><li v-for="route in report.routes" :key="route">{{ routes[route] || route }}</li></ul>
      <p v-else>未发现可归类的已观察阻断。</p>
      <p v-if="report.latestCandidateEvidenceUnavailable">最近候选缺少匹配回执，当前展示原件证据。</p>
      <p v-if="report.hostFailureDiagnosticMatched && !report.loaderReceiptMatched">执行未返回完整装载回执，以下为匹配本次输入的宿主故障记录。</p>
      <p v-if="report.missingMaterials.length">待补充：{{ report.missingMaterials.map(item => materials[item] || item).join('、') }}</p>
      <details v-if="report.transportEvidence"><summary>连接失败明细</summary>
        <p>{{ report.transportEvidence.scope === 'last_recorded_observation' ? '以下为执行中断前最后保存的连接观察，不一定是中断原因。' : '以下为本次回执中的连接尝试，标注的动作仅表示宿主调用区间。' }}不代表当前仍不可用。</p>
        <ul v-if="report.transportEvidence.observations.length"><li v-for="(row, index) in report.transportEvidence.observations"
          :key="index">{{ transportLabel(row) }}</li></ul>
        <p v-if="report.transportEvidence.observationsDropped">采集时另有 {{ report.transportEvidence.observationsDropped }} 条明细未保留。</p>
      </details>
      <details v-if="report.cooldownEvidence"><summary>限流响应明细</summary>
        <p>历史响应值，不是当前剩余等待时间，不会据此重新计时或自动重试。</p>
        <ul><li v-for="(row, index) in report.cooldownEvidence.observations" :key="index">{{ cooldownLabel(row) }}</li></ul>
      </details>
      <details v-if="report.dnsEvidence"><summary>DNS 解析明细</summary>
        <p>{{ report.dnsEvidence.scope === 'last_recorded_observation' ? '执行中断前最后保存的解析观察。' : '本次执行记录的解析阶段，缓存复用不计作新解析。' }}不代表当前解析结果，也未确认是最终故障原因。</p>
        <ul><li v-for="(row, index) in report.dnsEvidence.observations" :key="index">{{ dnsLabel(row) }}</li></ul>
      </details>
      <details v-if="report.findings.length"><summary>故障定位</summary>
        <ul><li v-for="(finding, index) in report.findings" :key="index">
          <span v-if="finding.node">{{ finding.node }} · </span>{{ findingLabel(finding) }}<span v-if="finding.failureType"> · {{ finding.failureType }}</span>
          <p v-if="contractLabel(finding)">{{ contractLabel(finding) }}</p>
          <p v-if="httpLabel(finding)">{{ httpLabel(finding) }}</p>
          <p v-if="networkDenialLabel(finding)">{{ networkDenialLabel(finding) }}</p>
          <p v-if="phaseLabel(finding)">{{ phaseLabel(finding) }}</p>
        </li></ul>
      </details>
    </template>
  </section>
</template>

<style scoped>
.enhanced-diagnostics { margin: 12px 0; border-block: 1px solid #e4e7ed; padding: 12px 0; }
p, li { overflow-wrap: anywhere; line-height: 1.6; }
ul { padding-left: 22px; }
.el-alert { margin-top: 10px; }
</style>
