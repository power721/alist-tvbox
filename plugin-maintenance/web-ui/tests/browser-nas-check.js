(async () => {
  const assert = (condition, message) => { if (!condition) throw Error(message) }
  const until = async (predicate, message) => {
    const deadline = Date.now() + 15000
    while (Date.now() < deadline) {
      if (predicate()) return
      await new Promise(resolve => setTimeout(resolve, 40))
    }
    throw Error(message)
  }
  const click = (text, root = document) => {
    const target = [...root.querySelectorAll('button')].find(button => button.textContent.trim() === text)
    assert(target && !target.disabled, `unavailable: ${text}`)
    target.click()
  }
  const base = '/api/plugin-maintenance'
  const sources = await (await fetch(base + '/sources')).json()
  const checks = []
  click('NAS 目录快照')
  await until(() => document.querySelector('.nas-inventory-picker'), 'snapshot input missing')
  const dialog = document.querySelector('.nas-inventory-picker').closest('.el-dialog')
  const fixtures = window.__nasInventoryFixtures
  assert(fixtures?.at?.files.length === 311 && fixtures?.t4?.files.length === 0, 'real inventories required')
  const originalFetch = window.fetch
  let requests = 0
  window.fetch = (...args) => { requests++; return originalFetch(...args) }
  async function load(value) {
    const input = dialog.querySelector('.nas-inventory-picker')
    const data = new DataTransfer()
    data.items.add(new File([JSON.stringify(value)], 'nas-inventory.json', { type: 'application/json' }))
    input.files = data.files
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await until(() => !dialog.querySelector('.nas-actions button').classList.contains('is-loading'), 'snapshot read did not settle')
  }
  const rows = () => dialog.querySelectorAll('.nas-inventory-table .el-table__body-wrapper tr')
  try {
    await load(fixtures.at)
    await until(() => rows().length === 311, 'AT inventory rows mismatch')
    assert(dialog.textContent.includes('Python 205') && dialog.textContent.includes('JavaScript 106'), 'AT type counts wrong')
    assert(dialog.textContent.includes('310 / 311') && dialog.textContent.includes('AT 静态目录'), 'AT read boundary wrong')
    assert(dialog.textContent.includes('本地文件 · 未签名') && dialog.textContent.includes('不代表当前装载状态'), 'trust label missing')
    checks.push('real AT static inventory: 205 Python / 106 JavaScript; 310 hashes / 311 entries; unsigned boundary')
    dialog.querySelector('input[value="python"]').click()
    await until(() => rows().length === 205, 'Python filter failed')
    dialog.querySelector('input[value="javascript"]').click()
    await until(() => rows().length === 106, 'JavaScript filter failed')
    const query = dialog.querySelector('input[aria-label="筛选 NAS 相对路径"]')
    query.value = 'tvbox-main/tvbox-main/js/index.js'
    query.dispatchEvent(new Event('input', { bubbles: true }))
    await until(() => rows().length === 1, 'path filter failed')
    assert(rows()[0].textContent.includes('大小不合规') && rows()[0].textContent.includes('未读取'), 'oversize entry promoted')
    checks.push('Python/JavaScript/path filters preserve the oversize file as unread, never passed')
    const tab = [...dialog.querySelectorAll('[role=tab]')].find(item => item.textContent.includes('快照绑定'))
    tab.click()
    await until(() => dialog.textContent.includes('导入文件声明，未进行在线身份复验'), 'binding boundary missing')
    const bytes = new TextEncoder().encode(JSON.stringify(fixtures.at))
    const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))]
      .map(value => value.toString(16).padStart(2, '0')).join('')
    assert(dialog.textContent.includes(hash) && dialog.textContent.includes(fixtures.at.scannerSha256), 'byte/bundle binding missing')
    checks.push('snapshot byte hash, scanner hash and independent container/mount identities remain visible')
    const fileTab = [...dialog.querySelectorAll('[role=tab]')].find(item => item.textContent.includes('文件盘点'))
    fileTab.click()
    await load({ ...fixtures.at, secret: 'PRIVATE_SENTINEL_DO_NOT_RENDER' })
    await until(() => dialog.textContent.includes('NAS 快照结构或边界不符合要求'), 'extra fields accepted')
    assert(!dialog.textContent.includes('PRIVATE_SENTINEL_DO_NOT_RENDER') && rows().length === 0, 'bad snapshot retained or leaked')
    checks.push('unknown-field snapshot refused without rendering credentials or retaining old rows')
    await load(fixtures.t4)
    await until(() => dialog.textContent.includes('T4 自定义目录'), 'T4 owner missing')
    assert(dialog.textContent.includes('Python 0') && dialog.textContent.includes('JavaScript 0')
      && dialog.textContent.includes('0 / 0') && rows().length === 0, 'empty T4 inventory confused with AT')
    checks.push('real T4 custom directory is separately empty; no stale AT files or invented source success')
    assert(requests === 0, 'snapshot preview unexpectedly called an API')
    checks.push('snapshot preview, filtering and validation make zero network requests and do not import or execute sources')
    assert(document.documentElement.scrollWidth <= innerWidth, 'page has horizontal overflow')
    const bounds = dialog.getBoundingClientRect()
    assert(bounds.left >= 0 && bounds.right <= innerWidth, 'dialog exceeds viewport')
    checks.push('dialog and identity text remain within desktop/mobile viewport')
  } finally { window.fetch = originalFetch; delete window.__nasInventoryFixtures }
  const after = await (await fetch(base + '/sources')).json()
  assert(JSON.stringify(after) === JSON.stringify(sources), 'source registry changed during snapshot preview')
  return { passed: checks.length, checks, viewport: { width: innerWidth, height: innerHeight },
    productionCalled: false, sourceExecuted: false, realModelCalled: false, published: false }
})()
