(async () => {
  const sample = window.__runtimeRepairReceipt
  const { state } = window.__modelFixture
  const regressed = window.__runtimeStageScenario === 'regressed'
  const checks = []
  const require = (condition, name) => { if (!condition) throw Error(name); checks.push(name) }
  const wait = async predicate => {
    for (let i = 0; i < 100; i++) {
      if (predicate()) return
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    throw Error('RUNTIME_STAGE_UI_WAIT_FAILED')
  }
  await wait(() => [...document.querySelectorAll('button')].some(button => button.textContent.trim() === '刷新会话'))
  const fixtureId = state.repair.id
  Object.assign(state.repair, sample.repair, { id: fixtureId })
  const candidate = structuredClone(sample.runtimeCandidate)
  if (regressed) {
    candidate.status = 'REJECTED'
    candidate.errorCode = 'RUNTIME_TARGET_UNRESOLVED'
    candidate.result.observedRegressions = ['playerStatus', 'mediaStatus']
    candidate.result.runtimeStageComparison = [
      { field: 'playerStatus', before: 'returned', after: 'policy_denied' },
      { field: 'mediaStatus', before: 'prefix_returned', after: 'not_requested' }
    ]
    state.repair.status = 'AWAITING_PROPOSAL'
  }
  state.candidates = [candidate]
  document.querySelector('[role="status"]').textContent = regressed
    ? '离线退化坏例：NAS回执投影加合成的原件成功状态；没有执行爬虫、模型或发布。'
    : 'NAS历史修复回执的离线显示；没有访问NAS或重新执行。修复会话ID映射到界面夹具。'
  const refresh = [...document.querySelectorAll('button')].find(button => button.textContent.trim() === '刷新会话')
  refresh.click()
  await wait(() => document.querySelector('.candidate .runtime-stages'))
  const card = document.querySelector('.candidate')
  const rows = [...card.querySelectorAll('.runtime-stages .el-table__body tbody tr')]
  require(rows.length === 2, 'player_and_media_have_separate_comparison_rows')
  require(rows[0].innerText.includes('出站策略拒绝'), 'real_candidate_player_denial_is_visible')
  require(rows[1].innerText.includes('未请求'), 'unrequested_media_is_not_called_successful')
  require(card.querySelector('.runtime-denials')?.innerText.includes('EGRESS_NONPUBLIC_TARGET_DENIED'),
    'exact_projected_denial_code_is_visible')
  require(card.innerText.includes('业务与播放未验证') && card.innerText.includes('候选尚未获得发布资格'),
    'runtime_observations_do_not_claim_playback_or_publication')
  if (regressed) {
    require(card.querySelector('.el-alert')?.innerText.includes('观察到退化：取链、媒体前缀'),
      'regressions_are_prominent_even_when_home_recovers')
    require(rows[0].innerText.includes('已返回') && rows[1].innerText.includes('已取回有界前缀'),
      'previous_success_is_preserved_in_comparison')
    require(card.innerText.includes('已拒绝'), 'regressed_candidate_is_not_promoted')
  } else {
    require(rows[0].innerText.includes('未调用') && rows[1].innerText.includes('未请求'),
      'historical_baseline_statuses_are_not_invented')
  }
  card.querySelector('button[aria-label^="拆解第"]').click()
  await wait(() => document.querySelector('.repair-runtime-drawer')?.innerText.includes('EGRESS_NONPUBLIC_TARGET_DENIED'))
  const drawer = document.querySelector('.repair-runtime-drawer')
  require(drawer.innerText.includes(candidate.sha256), 'drawer_keeps_exact_candidate_source_identity')
  require(drawer.innerText.includes('deniedCodes'), 'drawer_prioritizes_policy_denial_before_bounded_details')
  await wait(() => drawer.getAnimations().every(animation => animation.playState !== 'running')
    && drawer.getBoundingClientRect().right <= innerWidth + 1)
  const box = drawer.getBoundingClientRect()
  require(box.left >= -1 && box.right <= innerWidth + 1
    && document.documentElement.scrollWidth <= innerWidth + 1, 'runtime_drawer_and_page_fit_viewport')
  require(!drawer.innerText.includes('FN_NAS_PASSWORD') && !drawer.innerText.includes('Authorization'),
    'projected_runtime_evidence_has_no_authentication_fields')
  require(state.writes.length === 0 && state.generations === 0, 'review_performs_no_model_runtime_or_release_mutation')
  return { passed: checks.length, checks, viewport: { width: innerWidth, height: innerHeight },
    evidenceClass: regressed ? 'offline_regression_example_with_recorded_nas_projection' : 'recorded_nas_projection_offline_ui',
    repairIdRemapped: true, sourceExecuted: false, nasAccessed: false, modelCalled: false, published: false }
})()
