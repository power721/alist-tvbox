(async () => {
  const checks = []
  const require = (ok, name) => { if (!ok) throw Error(name); checks.push(name) }
  const wait = async predicate => {
    for (let i = 0; i < 100; i++) {
      if (predicate()) return
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    throw Error('DIAGNOSTIC_UI_WAIT_FAILED')
  }
  const original = window.fetch
  const marker = 'NAS 诊断展示回放，未在本机执行'
  let replayed = false
  window.fetch = async (...args) => {
    const response = await original(...args)
    const url = String(args[0] instanceof Request ? args[0].url : args[0])
    if (!/\/sources\/[a-f0-9-]+\/probe-runs$/.test(url) || args[1]?.method === 'POST') return response
    const rows = await response.clone().json()
    if (!rows[0]?.result || !window.__managedDiagnosticFixture) return response
    rows[0].status = 'FAILED'
    rows[0].errorCode = 'MANAGED_CLEANUP_UNCONFIRMED'
    rows[0].result.boundary = marker
    rows[0].result.managedExecutionDiagnostic = window.__managedDiagnosticFixture
    const node = rows[0].result.nodes.find(value => value.id === 'S06')
    node.reason = 'MANAGED_CLEANUP_UNCONFIRMED'
    node.status = 'blocked'
    replayed = true
    return new Response(JSON.stringify(rows), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    document.querySelector('.evidence-drawer .el-drawer__close-btn')?.click()
    document.querySelector('button.source.active')?.click()
    await wait(() => replayed && document.body.innerText.includes(marker))
    require(replayed, 'real_broker_diagnostic_replayed_in_browser_only')
    document.querySelector('button[aria-label^="拆解 S06 "]')?.click()
    await wait(() => [...document.querySelectorAll('[role="tab"]')].some(tab => tab.textContent === '失败诊断'))
    const tab = [...document.querySelectorAll('[role="tab"]')].find(tab => tab.textContent === '失败诊断')
    tab.click()
    await wait(() => document.querySelector('.evidence-drawer')?.innerText.includes('failureStage'))
    const drawer = document.querySelector('.evidence-drawer')
    require(drawer.innerText.includes('container_create') && drawer.innerText.includes('ENGINE_TIMEOUT'),
      'primary_failure_stage_and_code_visible')
    require(drawer.innerText.includes('createConfirmed: false') && drawer.innerText.includes('createAckBytes: 0'),
      'missing_create_ack_stays_explicit')
    require(drawer.innerText.includes('elapsedMillis: 6030'), 'engine_timing_visible')
    require(drawer.innerText.includes('阻塞') && !drawer.innerText.includes('init_returned'),
      'failure_data_cannot_promote_initialization')
    const rectangle = drawer.getBoundingClientRect()
    require(rectangle.left >= -1 && rectangle.width <= innerWidth + 1
      && document.documentElement.scrollWidth <= innerWidth + 1, 'diagnostic_drawer_fits_viewport')
    return { passed: checks.length, checks, viewport: { width: innerWidth, height: innerHeight },
      evidenceClass: 'real_broker_diagnostic_ui_replay', storedInDatabase: false, sourceExecuted: false }
  } finally {
    window.fetch = original
    delete window.__managedDiagnosticFixture
  }
})()
