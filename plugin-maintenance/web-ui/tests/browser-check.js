(async () => {
  const checks = []
  const base = '/api/plugin-maintenance'
  const assert = (condition, message) => { if (!condition) throw Error(message) }
  const until = async (predicate, message) => {
    const deadline = Date.now() + 15000
    while (Date.now() < deadline) {
      if (predicate()) return
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    throw Error(message)
  }
  const click = (text, root = document) => {
    const button = [...root.querySelectorAll('button')].find(item => item.textContent.trim() === text)
    assert(button && !button.disabled, `button unavailable: ${text}`)
    button.click()
  }
  const api = async path => {
    const response = await fetch(base + path, { credentials: 'same-origin' })
    assert(response.ok, `GET ${path}: ${response.status}`)
    return response.json()
  }
  const sources = await api('/sources')
  const source = sources.find(item => item.filename === 'SeedHub.py')
  assert(source, 'real directory source required')
  const sourceButton = [...document.querySelectorAll('button.source')].find(button =>
    button.querySelector('strong')?.textContent === source.name)
  assert(sourceButton, 'real source navigation missing')
  sourceButton.click()
  await until(() => document.querySelectorAll('.phase-item').length === 5, 'source report did not load')
  const runs = await api(`/sources/${source.id}/probe-runs`)
  const run = runs[0]
  assert(run?.status === 'COMPLETED' && run.executionMode === 'static', 'real source static run required')
  assert(run.result.nodes.filter(node => node.status === 'pass').length === 3, 'static pass scope changed')
  assert(run.result.nodes.filter(node => node.status === 'not_tested').length === 41, 'untested scope changed')
  const raw = await fetch(`${base}/sources/${source.id}/artifacts/${source.artifactSha256}`)
  const bytes = await raw.arrayBuffer()
  const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
    byte => byte.toString(16).padStart(2, '0')).join('')
  assert(digest === source.artifactSha256, 'saved source bytes differ')
  checks.push('real source artifact bytes match selected SHA; static 3 pass / 41 untested')
  const openDrawer = document.querySelector('.evidence-drawer .el-drawer__close-btn')
  if (openDrawer) { openDrawer.click(); await until(() => !document.querySelector('.el-overlay[style*="display: none"]') ||
    !document.querySelector('.evidence-drawer')?.offsetHeight, 'drawer close timeout') }
  if (document.querySelector('.evidence-drawer')?.offsetHeight) {
    await new Promise(resolve => setTimeout(resolve, 400))
  }
  assert(document.querySelectorAll('.phase-item').length === 5, 'five phases missing')
  const report = document.querySelector('.phase-strip').closest('.el-tab-pane')
  const phaseButton = [...document.querySelectorAll('.phase-item')].find(button => button.textContent.includes('业务数据'))
  phaseButton.click()
  await until(() => report.querySelectorAll('.el-table__body-wrapper tr').length === 11, 'business filter failed')
  assert(!report.querySelector('.el-table__body-wrapper').textContent.includes('STATIC_ANALYSIS'),
    'phase filter leaked static node')
  click('全部环节')
  await until(() => report.querySelectorAll('.el-table__body-wrapper tr').length === 44, 'all phases failed')
  checks.push('five phase summaries and business/all filters preserve 44 actual nodes')
  document.querySelector('button[aria-label="拆解 S03 语法与静态声明"]').click()
  await until(() => document.querySelector('.evidence-drawer')?.offsetHeight, 'evidence drawer missing')
  const drawer = document.querySelector('.evidence-drawer')
  const tabs = [...drawer.querySelectorAll('[role=tab]')]
  tabs.find(tab => tab.textContent.includes('验证要求')).click()
  await until(() => drawer.textContent.includes('输入来源'), 'node definition missing')
  tabs.find(tab => tab.textContent.includes('本机经验')).click()
  await until(() => drawer.textContent.includes('拒绝 Spider 中重复方法覆盖'), 'linked rule missing')
  assert(drawer.textContent.includes('references/core/instructions.md'), 'citation source path missing')
  checks.push('node drawer has bindings, definition, data and frozen cited local guidance')
  drawer.querySelector('.el-drawer__close-btn').click()
  await new Promise(resolve => setTimeout(resolve, 400))
  click('读取爬虫目录')
  await until(() => document.querySelector('.directory-picker'), 'directory picker missing')
  const originalFetch = window.fetch
  const token = `ui-receipt-${Date.now()}`
  const filename = `${token}.py`
  const code = `# ${token}\r\nraise RuntimeError("DO_NOT_EXECUTE_DIRECTORY_IMPORT")\r\nclass Spider: pass\r\n`
  const files = [
    ['选择目录/' + filename, code],
    ['选择目录/zz-duplicate.py', code],
    ['选择目录/invalid.py', new Uint8Array([0xff, 0xfe])],
    ['选择目录/.env', 'DIRECTORY_CONFIG_NOT_TO_UPLOAD'],
    ['选择目录/node_modules/dependency.py', 'DO_NOT_READ']
  ]
  const data = new DataTransfer()
  for (const [path, body] of files) {
    const file = new File([body], path.split('/').at(-1))
    Object.defineProperty(file, 'webkitRelativePath', { value: path })
    data.items.add(file)
  }
  const input = document.querySelector('.directory-picker')
  input.files = data.files
  input.dispatchEvent(new Event('change', { bubbles: true }))
  await until(() => document.querySelector('.directory-table')?.textContent.includes(filename), 'preview missing')
  const dialog = document.querySelector('.directory-picker').closest('.el-dialog')
  await until(() => dialog.textContent.includes('不是有效 UTF-8 文本'), 'invalid encoding was not blocked')
  assert((await api('/sources')).length === sources.length, 'preview uploaded a source')
  assert(dialog.textContent.includes('忽略 2 个'), 'non-Python/excluded files not ignored')
  checks.push('browser FileList preview rejects invalid UTF-8, blocks duplicate bytes and never uploads config')
  let sourcePosts = 0
  window.fetch = async (input, init) => {
    if (String(input) === base + '/sources' && init?.method === 'POST') {
      sourcePosts++
      const body = JSON.parse(init.body)
      assert(body.filename === filename && body.content === code, 'unexpected file uploaded')
      await originalFetch(input, init)
      throw TypeError('UI_TEST_LOST_RESPONSE')
    }
    return originalFetch(input, init)
  }
  try {
    const row = [...dialog.querySelectorAll('.el-table__body-wrapper tr')].find(item => item.textContent.includes(filename))
    click('确认导入', row)
    await until(() => dialog.textContent.includes('UI_TEST_LOST_RESPONSE'), 'uncertain state missing')
    assert(sourcePosts === 1, 'POST replayed')
    assert(row.querySelector('button').disabled, 'uncertain request is retryable')
  } finally { window.fetch = originalFetch }
  click('核对保存结果', dialog)
  await until(() => dialog.textContent.includes('已保存'), 'receipt reconciliation failed')
  const after = await api('/sources')
  assert(after.length === sources.length + 1, 'duplicate source created')
  assert(after.filter(item => item.filename === filename).length === 1, 'uncertain POST duplicated')
  checks.push('committed POST with lost response is not retried; GET reconciliation finds exactly one source')
  click('关闭', dialog)
  await new Promise(resolve => setTimeout(resolve, 400))
  assert(document.documentElement.scrollWidth <= innerWidth, 'viewport has horizontal overflow')
  return { passed: checks.length, checks, viewport: { width: innerWidth, height: innerHeight },
    realSourceSha256: digest, sourceBytes: bytes.byteLength, productionCalled: false,
    submittedSourceExecuted: false, realModelCalled: false }
})()
