(async () => {
  const { state, mode } = window.__modelFixture
  const checks = []
  const require = (condition, name) => { if (!condition) throw Error(name); checks.push(name) }
  const wait = async predicate => {
    for (let i = 0; i < 120; i++) {
      if (predicate()) return
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    throw Error('RUNTIME_ACCEPTANCE_UI_WAIT_FAILED')
  }
  const button = text => [...document.querySelectorAll('button')].find(item => item.textContent.trim() === text)
  require(mode === 'runtime-incomplete', 'isolated_offline_fixture')
  await wait(() => button('隔离复测候选') && !button('隔离复测候选').disabled)
  require(!document.querySelector('.runtime-acceptance'), 'static_check_does_not_claim_runtime_acceptance')
  button('隔离复测候选').click()
  await wait(() => document.querySelector('.runtime-required-nodes tbody tr'))
  require(state.writes.length === 1 && state.writes[0].path.endsWith('/runtime-validation'),
    'one_explicit_validation_no_other_mutations')
  require(state.writes[0].body.expectedCandidateSha256 === state.candidateSha, 'validation_pins_candidate')
  require(state.repair.status === 'AWAITING_PROPOSAL', 'partial_observation_keeps_repair_open')
  require(state.candidates[0].result.repairSuccessful === false, 'no_repair_success')
  const rows = [...document.querySelectorAll('.runtime-required-nodes .el-table__body tbody tr')]
  require(rows.length === 8, 'all_required_nodes_rendered')
  require(rows.some(row => row.innerText.includes('C05') && row.innerText.includes('未测')),
    'unexecuted_search_stays_untested')
  require(rows.some(row => row.innerText.includes('S06') && row.innerText.includes('受阻')),
    'loader_initialization_is_mandatory')
  const reasonCells = [...document.querySelectorAll('.runtime-required-nodes .el-table__body td:last-child .cell')]
  require(reasonCells.some(cell => cell.innerText.trim() === 'LOADER_RESULT_UNVERIFIED'),
    'loader_block_reason_is_visible')
  require(reasonCells.every(cell => cell.scrollWidth <= cell.clientWidth + 1),
    'required_node_evidence_fits_each_cell')
  require(document.querySelector('.runtime-acceptance').innerText.includes('修复未完成'),
    'partial_result_label_is_explicit')
  require(document.querySelector('.candidate').innerText.includes('首页恢复观察：已观察到'),
    'partial_recovery_evidence_is_preserved')
  require(!document.querySelector('textarea').disabled && state.repair.stopCode === 'REQUIRED_RUNTIME_NODES_INCOMPLETE',
    'repair_can_continue_without_changing_baseline')
  require(document.documentElement.scrollWidth <= innerWidth + 1, 'required_nodes_fit_viewport')
  document.querySelector('button[aria-label="拆解第 1 轮运行证据"]').click()
  await wait(() => document.querySelector('.repair-runtime-drawer')?.innerText.includes('LOADER_REQUIRED_RESULTS_UNVERIFIED'))
  require(document.querySelector('.repair-runtime-drawer').innerText.includes('successful: false'),
    'drawer_preserves_incomplete_acceptance')
  require(document.documentElement.scrollWidth <= innerWidth + 1, 'evidence_drawer_fits_viewport')
  document.querySelector('.repair-runtime-drawer .el-drawer__close-btn').click()
  await wait(() => document.querySelector('.repair-runtime-drawer')?.getAttribute('aria-hidden') === 'true'
    || !document.querySelector('.el-overlay')?.offsetHeight)
  document.querySelector('.runtime-acceptance').scrollIntoView({ block: 'start' })
  return { evidenceClass: 'offline_ui_fixture_only', mode, passed: checks.length, checks,
    viewport: { width: innerWidth, height: innerHeight }, realSourceExecuted: false, published: false }
})()
