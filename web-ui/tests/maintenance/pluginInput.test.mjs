import assert from 'node:assert/strict'
import test from 'node:test'
import { inputStages } from '../../src/maintenance/pluginInput.ts'

const source = 'a'.repeat(64)
const input = { artifactSha256: source, evidence: { inputFormat: 'secspider/1', inputSha256: 'b'.repeat(64),
  inputBytes: 900, plaintextSha256: source, plaintextBytes: 100, atvpSha256: 'c'.repeat(64),
  signatureVerified: true, declaredHashVerified: true, syntaxCompiled: true } }

test('encrypted input separates package, runtime and plaintext identities', () => {
  const rows = inputStages(input, source)
  assert.equal(rows[0].sha256, 'b'.repeat(64))
  assert.equal(rows[1].status, '同钥验签通过')
  assert.equal(rows[2].sha256, source)
  assert.equal(rows[3].status, '与导入原文一致')
  assert.equal(rows[4].status, '未由导入过程验证')
})

test('an edited source never inherits package signature coverage', () => {
  assert.equal(inputStages(input, 'd'.repeat(64))[3].status, '原包不覆盖当前版本')
  const mismatched = { ...input, evidence: { ...input.evidence, plaintextSha256: 'e'.repeat(64) } }
  assert.equal(inputStages(mismatched, source)[3].status, '原包不覆盖当前版本')
})

test('raw input has no signature and unverified encrypted input never shows a pass', () => {
  const raw = { ...input, evidence: { ...input.evidence, inputFormat: 'python',
    signatureVerified: null, declaredHashVerified: null } }
  assert.equal(inputStages(raw, source)[1].status, '明文输入，无包签名')
  const unchecked = { ...input, evidence: { ...input.evidence, signatureVerified: null, declaredHashVerified: null } }
  assert.equal(inputStages(unchecked, source)[1].status, '未通过')
  assert.equal(inputStages(unchecked, source)[2].status, '未通过')
})

test('AT provenance stays separate from signature and business acceptance', () => {
  const observed = { ...input, evidence: { ...input.evidence, inputFormat: 'python',
    signatureVerified: null, atSource: { pluginId: 7, sourceSha256: 'f'.repeat(64) } } }
  const rows = inputStages(observed, source)
  assert.equal(rows[0].stage, 'AT 来源')
  assert.equal(rows[0].sha256, 'f'.repeat(64))
  assert.equal(rows[2].status, '明文输入，无包签名')
  assert.equal(rows.at(-1).status, '未由导入过程验证')
})
