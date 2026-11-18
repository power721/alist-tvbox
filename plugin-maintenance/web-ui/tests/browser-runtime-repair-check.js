(async () => {
  const sample = window.__runtimeRepairReceipt
  const checks = []
  const require = (condition, name) => { if (!condition) throw Error(name); checks.push(name) }
  const wait = async predicate => {
    for (let i = 0; i < 160; i++) {
      if (predicate()) return
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    throw Error('REPAIR_UI_WAIT_FAILED')
  }
  const original = window.fetch
  let requested = false
  const json = value => new Response(JSON.stringify(value), {
    status: 200, headers: { 'Content-Type': 'application/json' }
  })
  window.fetch = async (...args) => {
    const path = new URL(String(args[0] instanceof Request ? args[0].url : args[0]), location.href).pathname
    const method = args[1]?.method || 'GET'
    if (/\/sources\/[a-f0-9-]+\/repairs$/.test(path) && method === 'GET')
      return json([{ ...sample.repair, status: requested ? 'RUNTIME_OBSERVED' : 'AWAITING_RUNTIME_VALIDATION' }])
    if (path.endsWith(`/repairs/${sample.repair.id}`) && method === 'GET')
      return json({ ...sample.repair, status: requested ? 'RUNTIME_OBSERVED' : 'AWAITING_RUNTIME_VALIDATION' })
    if (path.endsWith(`/repairs/${sample.repair.id}/candidates`) && method === 'GET')
      return json([requested ? sample.runtimeCandidate : sample.staticCandidate])
    if (path.endsWith(`/repair-candidates/${sample.runtimeCandidate.id}/runtime-validation`)) {
      require(method === 'POST', 'runtime_button_posts_validation')
      const body = JSON.parse(args[1].body)
      require(body.expectedCandidateSha256 === sample.runtimeCandidate.sha256, 'runtime_button_pins_candidate_sha')
      requested = true
      return json(sample.runtimeCandidate)
    }
    return original(...args)
  }
  try {
    document.querySelector('button.source.active')?.click()
    await wait(() => [...document.querySelectorAll('[role="tab"]')].some(tab => tab.textContent === '修复候选'))
    const tab = [...document.querySelectorAll('[role="tab"]')].find(value => value.textContent === '修复候选')
    tab.click()
    const refresh = [...document.querySelectorAll('button')].find(button => button.textContent.trim() === '刷新会话')
    refresh.click()
    await wait(() => [...document.querySelectorAll('button')].some(button => button.textContent.trim() === '隔离复测候选'))
    require(!document.querySelector('.candidate table'), 'static_candidate_does_not_claim_runtime_result')
    const replay = document.createElement('p')
    replay.textContent = 'NAS 真实修复回执回放；本机未执行该修复。'
    replay.setAttribute('role', 'status')
    const validate = [...document.querySelectorAll('button')].find(button => button.textContent.trim() === '隔离复测候选')
    validate.click()
    await wait(() => document.querySelector('.candidate')?.innerText.includes('首页恢复观察：已观察到'))
    require(requested, 'runtime_action_completed_from_recorded_nas_receipt')
    const card = document.querySelector('.candidate')
    card.prepend(replay)
    require(replay.isConnected && replay.innerText.includes('本机未执行'), 'nas_receipt_replay_is_explicitly_marked')
    require(card.innerText.includes('业务与播放未验证') && card.innerText.includes('未获发布资格'),
      'runtime_observation_keeps_business_and_release_boundaries')
    const rows = [...card.querySelectorAll('.el-table__body tbody tr')]
    require(rows.length === 10, 'ten_before_after_fields_rendered')
    const home = rows.find(row => row.innerText.includes('首页条目'))
    require(home && /首页条目\s+0\s+20/.test(home.innerText), 'home_change_zero_to_twenty_rendered')
    require(!card.innerText.includes('Authorization') && !card.innerText.includes('FN_NAS_PASSWORD'),
      'projected_repair_has_no_authentication_fields')
    require(document.documentElement.scrollWidth <= innerWidth + 1, 'repair_view_fits_viewport')
    card.scrollIntoView({ block: 'start' })
    return { passed: checks.length, checks, viewport: { width: innerWidth, height: innerHeight },
      evidenceClass: 'real_nas_repair_receipt_ui_replay', storedInDatabase: false }
  } finally {
    window.fetch = original
    delete window.__runtimeRepairReceipt
  }
})()
