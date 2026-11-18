 (async () => {
  const checks = []
  const require = (ok, name) => { if (!ok) throw Error(name); checks.push(name) }
  const wait = async predicate => {
    for (let i = 0; i < 160; i++) {
      if (predicate()) return
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    throw Error('BUSINESS_UI_WAIT_FAILED')
  }
  const original = window.fetch
  const sample = window.__businessProductRun
  const marker = 'NAS 产品任务展示回放，未在本机执行'
  let replayed = false
  window.fetch = async (...args) => {
    const response = await original(...args)
    const url = String(args[0] instanceof Request ? args[0].url : args[0])
    if (!/\/sources\/[a-f0-9-]+\/probe-runs$/.test(url) || args[1]?.method === 'POST') return response
    const value = structuredClone(sample)
    value.result.boundary = marker
    replayed = true
    return new Response(JSON.stringify([value]), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  try {
    document.querySelector('.evidence-drawer .el-drawer__close-btn')?.click()
    document.querySelector('button.source.active')?.click()
    await wait(() => replayed && document.querySelector('.business-sample'))
    const text = document.querySelector('.business-sample').innerText
    require(text.includes('卡片 20') && text.includes('第二页 4') && text.includes('选集 102'),
      'real_product_counts_rendered_in_original_task_panel')
    require(document.body.innerText.includes(marker) && text.includes('未独立核验'),
      'replay_and_untrusted_business_boundary_visible')
    document.querySelector('button[aria-label="拆解采样 C03"]').click()
    await wait(() => document.querySelector('.evidence-drawer')?.innerText.includes('page2Items: 4'))
    const drawer = document.querySelector('.evidence-drawer')
    require(drawer.innerText.includes('T4_SAMPLE_OUTPUT_UNVERIFIED') && drawer.innerText.includes('阻塞'),
      'business_observation_does_not_promote_node')
    const tab = [...drawer.querySelectorAll('[role="tab"]')].find(value => value.textContent === '执行器与 HTTP')
    require(Boolean(tab), 'original_drawer_exposes_executor_and_http_tab')
    tab.click()
    await wait(() => drawer.innerText.includes('responseStatus: 200'))
    require(drawer.innerText.includes('httpObservations') && drawer.innerText.includes('confirmed: true'),
      'host_http_and_cleanup_data_visible')
    require(!drawer.innerText.includes('Authorization') && !drawer.innerText.includes('FN_NAS_PASSWORD'),
      'no_authentication_fields_in_projected_evidence')
    const bounds = drawer.getBoundingClientRect()
    require(bounds.left >= -1 && bounds.width <= innerWidth + 1
      && document.documentElement.scrollWidth <= innerWidth + 1, 'business_drawer_fits_viewport')
    const player = sample.result.managedSourceEvidence.untrustedRuntimeSummary
    if (typeof player.playerStatus === 'string') {
      drawer.querySelector('.el-drawer__close-btn')?.click()
      document.querySelector('button[aria-label="拆解采样 R01"]').click()
      await wait(() => document.querySelector('.evidence-drawer')?.innerText
        .includes(`playerStatus: ${player.playerStatus}`))
      const playerDrawer = document.querySelector('.evidence-drawer')
      const playerNode = sample.result.nodes.find(node => node.id === 'R01')
      require(playerDrawer.innerText.includes(playerNode.reason)
        && playerDrawer.innerText.includes('阻塞'), 'player_block_reason_and_observation_rendered')
      playerDrawer.querySelector('.el-drawer__close-btn')?.click()
      document.querySelector('button[aria-label="拆解采样 M01"]').click()
      await wait(() => document.querySelector('.evidence-drawer')?.innerText
        .includes(`mediaStatus: ${player.mediaStatus}`))
      const mediaDrawer = document.querySelector('.evidence-drawer')
      const mediaNode = sample.result.nodes.find(node => node.id === 'M01')
      require(mediaDrawer.innerText.includes(mediaNode.reason)
        && mediaDrawer.innerText.includes('阻塞'), 'media_prerequisite_is_not_reported_as_playback')
      const mediaBounds = mediaDrawer.getBoundingClientRect()
      require(mediaBounds.left >= -1 && mediaBounds.width <= innerWidth + 1
        && document.documentElement.scrollWidth <= innerWidth + 1, 'player_media_drawer_fits_viewport')
    }
    return { passed: checks.length, checks, viewport: { width: innerWidth, height: innerHeight },
      evidenceClass: 'real_nas_product_receipt_ui_replay', sourceExecutedOnHost: false, storedInDatabase: false }
  } finally {
    window.fetch = original
    delete window.__businessProductRun
  }
})()
