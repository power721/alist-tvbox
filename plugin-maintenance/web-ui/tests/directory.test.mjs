import assert from 'node:assert/strict'
import { test } from 'node:test'
import { previewDirectory, sha256 } from '../src/directory.ts'

function file(path, body = 'class Spider: pass\r\n', override = {}) {
  const bytes = typeof body === 'string' ? new TextEncoder().encode(body) : body
  return { name: path.split('/').at(-1), size: bytes.byteLength, webkitRelativePath: path,
    arrayBuffer: async () => bytes.slice().buffer, ...override }
}

test('Chinese paths, BOM and CRLF preserve exact bytes and artifact hash', async () => {
  const body = '\ufeff# 中文目录\r\nclass Spider: pass\r\n'
  const selected = file('爬虫/测试源.py', body)
  const result = await previewDirectory([selected])
  assert.equal(result.root, '爬虫')
  assert.equal(result.entries[0].content, body)
  assert.equal(result.entries[0].name, '测试源')
  assert.equal(result.entries[0].sha256, await sha256(await selected.arrayBuffer()))
})
test('only py content is read; config and excluded directories are never read', async () => {
  let reads = 0
  const blocked = { arrayBuffer: async () => { reads++; throw Error('must not read') } }
  const result = await previewDirectory([file('root/.env', 'SECRET', blocked),
    file('root/config.json', '{}', blocked), file('root/.venv/mod.py', '', blocked),
    file('root/node_modules/mod.py', '', blocked), file('root/.hidden/mod.py', '', blocked),
    file('root/source.py')])
  assert.equal(reads, 0)
  assert.equal(result.ignored, 5)
  assert.equal(result.entries.length, 1)
})
test('path traversal, backslash, absolute and name mismatch are rejected before reading', async () => {
  for (const path of ['root/../evil.py', 'root/./evil.py', '/evil.py', 'root//evil.py', 'root\\evil.py']) {
    await assert.rejects(previewDirectory([file(path)]), /非法相对路径/)
  }
  await assert.rejects(previewDirectory([file('root/a.py', '', { name: 'other.py' })]), /非法相对路径/)
})
test('same basename in distinct directories retains distinct paths and content', async () => {
  const result = await previewDirectory([file('root/a/source.py'), file('root/b/source.py', '# other')])
  assert.deepEqual(result.entries.map(entry => entry.path), ['root/a/source.py', 'root/b/source.py'])
  assert.ok(result.entries.every(entry => entry.content))
})
test('duplicate paths and content are not importable', async () => {
  const result = await previewDirectory([file('root/a.py'), file('root/a.py'), file('root/b.py')])
  assert.equal(result.entries.filter(entry => entry.content !== undefined).length, 1)
  assert.match(result.entries[1].problem, /路径重复/)
  assert.match(result.entries[2].problem, /内容相同/)
})
test('empty, invalid UTF-8 and binary files cannot produce import content', async () => {
  for (const body of ['', ' \n ', new Uint8Array([0xff, 0xfe]), 'x\0y']) {
    const result = await previewDirectory([file('root/source.py', body)])
    assert.equal(result.entries[0].content, undefined)
    assert.ok(result.entries[0].problem)
  }
})
test('per-file size limit rejects before reading', async () => {
  let reads = 0
  const result = await previewDirectory([file('root/a.py', '', { size: 1_048_577,
    arrayBuffer: async () => { reads++; throw Error('must not read') } })])
  assert.equal(reads, 0)
  assert.match(result.entries[0].problem, /1 MiB/)
})
test('aggregate byte and file count budgets reject the whole preview', async () => {
  await assert.rejects(previewDirectory(Array.from({ length: 17 }, (_, i) =>
    file(`root/${i}.py`, '', { size: 1_048_576 }))), /16 MiB/)
  await assert.rejects(previewDirectory(Array.from({ length: 501 }, (_, i) => file(`root/${i}.py`))), /500/)
  await assert.rejects(previewDirectory(Array.from({ length: 2001 }, (_, i) => file(`root/${i}.json`))), /2000/)
})
test('read size mismatch, failed reads and long filenames remain blocked', async () => {
  const result = await previewDirectory([file('root/a.py', '', { size: 1 }),
    file('root/b.py', 'x', { arrayBuffer: async () => { throw Error('file unavailable') } }),
    file(`root/${'a'.repeat(200)}.py`)])
  assert.ok(result.entries.every(entry => entry.content === undefined && entry.problem))
})
test('cancelled previews never finish as importable data', async () => {
  await assert.rejects(previewDirectory([file('root/a.py')], () => true), /取消/)
})
