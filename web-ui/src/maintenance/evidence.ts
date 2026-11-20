export interface ProbeNode {
  id: string; title: string; status: string; reason: string; requirement: string; evidence?: unknown
}
export interface NodeDefinition { id: string; title: string; inputs?: string; assertions?: string; boundary?: string }
export interface GuidanceRule {
  id: string; title: string; guidance: string; validationSteps: string[]; nodes?: string[]
  citation: { skill: string; path: string; startLine: number; endLine: number; sha256: string; excerpt: string }
}
export interface KnowledgeContext {
  catalogSha256: string; rules: GuidanceRule[]; boundary: string
  auxiliaryEvidence?: { extractionReplay?: unknown; browserEvidence?: unknown }
}
export interface EvidenceChange {
  kind: 'extractionReplay' | 'browserEvidence' | 'selectorProposal'
  sourceId: string; artifactSha256: string; runId: string; evidence: unknown
}
export interface ManagedEvidence {
  executionKind?: string
  untrustedRuntimeSummary?: { status: string; [field: string]: string | number | null }
  httpObservations?: unknown[]
  deniedCodes?: string[]
}
export interface EvidenceRun {
  id: string; status: string; artifactSha256: string; executionMode: string; freshness: string
  profileSha256: string | null; runtimeSha256: string | null
  result: { nodes: ProbeNode[]; managedSourceEvidence?: ManagedEvidence; managedExecutionDiagnostic?: unknown } | null
  context: KnowledgeContext | null
}
export const phases = [
  { id: 'S', title: '结构与装载' }, { id: 'C', title: '业务数据' }, { id: 'R', title: '运行与取链' },
  { id: 'M', title: '媒体链路' }, { id: 'P', title: '客户端' }
]
export const statusLabels: Record<string, string> = { pass: '通过', fail: '失败', blocked: '阻塞', not_tested: '未测' }
export const requirementLabels: Record<string, string> = { required: '必需', optional: '可选', undeclared: '未声明', not_applicable: '不适用' }

export function mediaObservationLabel(observations: unknown[] = []): string {
  const formats: Record<string, string> = { hls: 'HLS 清单', mp4: 'MP4', mpeg_ts: 'MPEG-TS',
    error_document: '错误文档', unknown: '未识别格式' }
  for (const value of [...observations].reverse()) {
    if (!value || typeof value !== 'object') continue
    const row = value as Record<string, unknown>
    if (row.rangeRequested !== true || ![200, 206].includes(row.responseStatus as number)
        || !Number.isInteger(row.responseBytes) || (row.responseBytes as number) < 0
        || (row.responseBytes as number) > 65536 || typeof row.prefixFormat !== 'string'
        || !Object.prototype.hasOwnProperty.call(formats, row.prefixFormat)) continue
    return `${formats[row.prefixFormat]} · HTTP ${row.responseStatus} · ${row.responseBytes} 字节`
  }
  return '未观察'
}

export function phaseSummary(definitions: NodeDefinition[], nodes: ProbeNode[], phase: string) {
  const expected = definitions.filter(node => node.id.startsWith(phase))
  const counts = { total: expected.length, pass: 0, fail: 0, blocked: 0, not_tested: 0 }
  for (const definition of expected) {
    const matches = nodes.filter(node => node.id === definition.id)
    const status = matches.length === 1 ? matches[0]!.status : 'not_tested'
    if (status === 'pass' || status === 'fail' || status === 'blocked') counts[status]++
    else counts.not_tested++
  }
  return counts
}

export interface EvidenceField { id: string; label: string; children?: EvidenceField[] }
const secretFields = /^(authorization|cookie|set-cookie|password|passwd|secret|token|(?:x-)?api[_-]?key|access[_-]?token|session[_-]?token)$/i

export function evidenceFields(value: unknown): EvidenceField[] {
  let remaining = 200
  function visit(input: unknown, path: string, label: string, depth: number): EvidenceField {
    if (secretFields.test(label)) return { id: path, label: `${label}: [已隐藏]` }
    if (input !== null && typeof input === 'object') {
      if (depth >= 6) return { id: path, label: `${label}: [超过展示深度]` }
      const entries = Object.entries(input)
      const children: EvidenceField[] = []
      for (const [key, item] of entries) {
        if (--remaining < 0) { children.push({ id: `${path}/limit`, label: '[其余字段未展开]' }); break }
        children.push(visit(item, `${path}/${key}`, key, depth + 1))
      }
      return { id: path, label: `${label} (${entries.length})`, children }
    }
    const text = String(input)
    return { id: path, label: `${label}: ${text.length > 1200 ? text.slice(0, 1200) + ' [已截断]' : text}` }
  }
  return value === undefined ? [] : [visit(value, '$', '节点证据', 0)]
}
