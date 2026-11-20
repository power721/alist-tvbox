export interface PluginInputEvidence {
  inputFormat: 'python' | 'secspider/1'
  inputSha256: string
  inputBytes: number
  plaintextSha256: string
  plaintextBytes: number
  atvpSha256?: string
  runtimeSha256?: string
  processorSha256: string
  signatureVerified: boolean | null
  declaredHashVerified: boolean | null
  syntaxCompiled: boolean
  sourceExecuted: boolean
  businessChainVerified: boolean
  publishEligible: boolean
  authority: string
  atSource?: {
    instanceKey: string
    observationSha256: string
    observedAt: string
    pluginId: number
    sourceLocatorSha256: string
    sourceSha256: string
    requestMethod: 'GET'
    authority: 'at_static_source_read' | 'at_public_https_source_read' | 'at_embedded_source_read'
    atCredentialsForwarded?: boolean
    productionModified: false
  }
}

export interface PluginInputSummary {
  id: string | null
  sourceId: string | null
  artifactSha256: string
  evidenceSha256: string
  evidence: PluginInputEvidence
  createdAt: string | null
}

export function inputStages(input: PluginInputSummary, currentSha256: string) {
  const value = input.evidence
  const matches = input.artifactSha256 === currentSha256
    && value.plaintextSha256 === currentSha256
  const rows = [
    { stage: '原始输入', status: value.inputFormat, sha256: value.inputSha256, bytes: value.inputBytes },
    { stage: '签名核对', status: value.signatureVerified === true ? '同钥验签通过'
      : value.inputFormat === 'python' ? '明文输入，无包签名' : '未通过', sha256: value.atvpSha256 ?? '', bytes: null },
    { stage: '明文校验', status: value.syntaxCompiled && (value.inputFormat === 'python'
      || value.declaredHashVerified === true) ? '哈希与语法已核对' : '未通过',
      sha256: value.plaintextSha256, bytes: value.plaintextBytes },
    { stage: '当前版本', status: matches ? '与导入原文一致' : '原包不覆盖当前版本',
      sha256: currentSha256, bytes: null },
    { stage: '业务与发布', status: '未由导入过程验证', sha256: '', bytes: null }
  ]
  if (value.atSource) rows.unshift({ stage: 'AT 来源',
    status: `插件 ${value.atSource.pluginId} · 只读正文`,
    sha256: value.atSource.sourceSha256, bytes: value.inputBytes })
  return rows
}
