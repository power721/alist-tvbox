(async () => {
  if (!window.__modelFixture) {
    const checks = []
    const writes = []
    const mutations = []
    const require = (condition, name) => { if (!condition) throw Error(name); checks.push(name) }
    const wait = async predicate => {
      for (let i = 0; i < 400; i++) {
        if (predicate()) return
        await new Promise(resolve => setTimeout(resolve, 50))
      }
      throw Error('MODEL_HTTP_UI_WAIT_FAILED')
    }
    const originalFetch = window.fetch.bind(window)
    const api = async path => {
      const response = await originalFetch('/api/plugin-maintenance' + path, { credentials: 'same-origin' })
      if (!response.ok) throw Error(`MODEL_HTTP_READ_FAILED:${response.status}`)
      return response.json()
    }
    const button = text => [...document.querySelectorAll('button')].find(item => item.textContent.trim() === text)
    const modelWrites = suffix => writes.filter(row => row.path.endsWith(suffix))
    window.fetch = async (input, init) => {
      const path = new URL(typeof input === 'string' ? input : input.url, location.href).pathname
      const method = init?.method || 'GET'
      if (method !== 'GET') mutations.push(path)
      if (method !== 'GET' && (path.includes('/model-') || path.endsWith('/submit'))) {
        writes.push({ path, method, body: JSON.parse(init.body),
          csrfPresent: Object.keys(init.headers || {}).some(key => key.toLowerCase().includes('csrf')) })
      }
      return originalFetch(input, init)
    }
    try {
      await wait(() => button('添加静态修复示例') && !button('添加静态修复示例').disabled)
      button('添加静态修复示例').click()
      await wait(() => document.querySelector('.source.active') && document.body.innerText.includes('分析完成'))
      const tab = [...document.querySelectorAll('[role="tab"]')].find(item => item.textContent.trim() === '修复候选')
      require(!!tab, 'packaged_workbench_has_repair_tab')
      tab.click()
      await wait(() => button('从当前报告创建修复') && !button('从当前报告创建修复').disabled)
      button('从当前报告创建修复').click()
      await wait(() => button('审阅并授权源码') && !button('审阅并授权源码').disabled)
      require(writes.length === 0, 'opening_real_repair_never_authorizes_or_calls_model')
      require(button('请求模型建议').disabled, 'real_backend_starts_without_source_consent')
      button('审阅并授权源码').click()
      await wait(() => button('授权此源码版本'))
      require(button('授权此源码版本').disabled, 'real_consent_requires_review_checkbox')
      require(document.querySelector('.model-preview').innerText.includes('class Spider'), 'real_source_is_available_for_review')
      document.querySelector('.el-dialog input[type="checkbox"]').click()
      await wait(() => !button('授权此源码版本').disabled)
      button('授权此源码版本').click()
      await wait(() => !button('请求模型建议').disabled)
      require(modelWrites('/model-consent').length === 1 && modelWrites('/model-consent')[0].method === 'PUT',
        'real_source_consent_uses_put')
      require(modelWrites('/model-calls').length === 0, 'real_consent_does_not_request_model')
      button('请求模型建议').click()
      await wait(() => button('确认请求'))
      require(modelWrites('/model-calls').length === 0, 'real_confirmation_is_separate_from_sending')
      button('确认请求').click()
      await wait(() => button('提交模型候选并静态验证'))
      require(modelWrites('/model-calls').length === 1, 'real_ui_sends_one_explicit_model_request')
      const sent = modelWrites('/model-calls')[0].body
      require(sent.autoSubmit === false && !!sent.expectedFaultSha256 && !!sent.expectedConfigurationSha256,
        'real_model_request_pins_inputs_and_disables_auto_submission')
      require(modelWrites('/submit').length === 0, 'real_model_result_is_not_automatically_submitted')
      button('提交模型候选并静态验证').click()
      await wait(() => document.querySelector('.candidate')?.innerText.includes('静态验证通过'))
      require(document.querySelector('.repair-actions .el-select').textContent.includes('静态通过，等待运行时验证'),
        'real_repair_selector_tracks_polled_candidate_status')
      const sources = await api('/sources')
      require(sources.length === 1, 'real_workflow_uses_only_one_trusted_fixture_source')
      const repairs = await api(`/sources/${sources[0].id}/repairs`)
      const calls = await api(`/repairs/${repairs[0].id}/model-calls`)
      require(calls.length === 1 && !!calls[0].candidateId, 'real_candidate_is_linked_to_model_call')
      require(modelWrites('/submit').length === 1
        && modelWrites('/submit')[0].body.expectedProposalSha256 === calls[0].proposalSha256,
      'real_submission_pins_exact_stored_proposal_sha')
      require(document.querySelector('.candidate').innerText.includes(calls[0].id), 'real_window_displays_model_provenance')
      button('撤销源码授权').click()
      await wait(() => button('请求模型建议').disabled && !button('审阅并授权源码').disabled
        && !button('撤销源码授权'))
      require(modelWrites('/model-consent').at(-1).body.artifactSha256 === null, 'real_source_consent_is_revocable')
      require(writes.every(row => row.csrfPresent), 'real_mutations_keep_existing_csrf_contract')
      require(!mutations.some(path => path.endsWith('/runtime-validation')),
        'static_submission_never_requests_runtime_execution')
      require(document.documentElement.scrollWidth <= innerWidth + 1, 'packaged_workbench_fits_desktop')
      return { evidenceClass: 'packaged_ui_real_java_http_fake_provider', passed: checks.length, checks,
        sourceId: sources[0].id, repairId: repairs[0].id, modelCallId: calls[0].id, candidateId: calls[0].candidateId }
    } finally {
      window.fetch = originalFetch
    }
  }
  const { state, complete, mode } = window.__modelFixture
  const checks = []
  const require = (condition, name) => { if (!condition) throw Error(name); checks.push(name) }
  const wait = async predicate => {
    for (let i = 0; i < 120; i++) {
      if (predicate()) return
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    throw Error('MODEL_UI_WAIT_FAILED')
  }
  const button = text => [...document.querySelectorAll('button')].find(item => item.textContent.trim() === text)
  const writes = suffix => state.writes.filter(row => row.path.endsWith(suffix))
  const refresh = () => document.querySelector('button[aria-label="刷新模型状态"]').click()
  if (mode === 'release-observed') {
    await wait(() => button('保存发布准备计划') && document.querySelector('.el-select'))
    require(state.writes.length === 0, 'opening_preparation_never_creates_a_plan_or_executes_anything')
    require(state.repair.status === 'AWAITING_PROPOSAL', 'partial_observation_keeps_repair_open')
    require(button('保存发布准备计划').disabled, 'incomplete_loader_validation_cannot_prepare_release')
    require(writes('/release-plans').length === 0 && state.plans.length === 0,
      'partial_observation_creates_no_preparation_plan')
    require(document.documentElement.scrollWidth <= innerWidth + 1, 'preparation_window_fits_viewport')
    return { evidenceClass: 'offline_ui_fixture_only', mode, passed: checks.length, checks,
      realModelCalled: false, sourceExecuted: false, nasAccessed: false, published: false }
  }
  await wait(() => button('请求模型建议') && !document.querySelector('button[aria-label="刷新模型状态"]').disabled)
  require(state.writes.length === 0, 'opening_the_repair_window_never_authorizes_or_calls_a_model')
  if (mode === 'revoked') {
    require(button('请求模型建议').disabled, 'revoked_consent_blocks_generation')
    require(button('提交模型候选并静态验证').disabled, 'revoked_consent_blocks_candidate_submission')
  } else if (mode === 'stale') {
    require(button('请求模型建议').disabled, 'stale_source_cannot_generate')
    require(button('审阅并授权源码').disabled, 'stale_source_cannot_authorize')
    require(button('提交模型候选并静态验证').disabled, 'stale_source_cannot_submit_stored_proposal')
  } else if (mode === 'stored') {
    require(button('请求模型建议').disabled, 'disabled_model_cannot_generate')
    require(!button('提交模型候选并静态验证').disabled, 'disabled_model_does_not_block_reviewed_existing_proposal')
    button('提交模型候选并静态验证').click()
    await wait(() => document.querySelector('.candidate')?.innerText.includes('模型来源'))
    require(writes('/submit')[0].body.expectedProposalSha256 === state.proposalSha, 'submission_pins_stored_proposal_sha')
    require(writes('/model-calls').length === 0, 'stored_proposal_submission_makes_no_new_model_call')
  } else {
    if (mode === 'full') {
      require(button('请求模型建议').disabled, 'disabled_and_unauthorized_model_cannot_generate')
      button('审阅并授权源码').click()
      await wait(() => button('授权此源码版本'))
      require(button('授权此源码版本').disabled, 'source_consent_requires_explicit_review_checkbox')
      require(document.querySelector('.model-preview').innerText.includes('class Spider'), 'complete_source_is_visible_for_review')
      const reference = [...document.querySelectorAll('summary')].find(item => item.textContent === '冻结引用资料')
      reference.click()
      require(document.body.innerText.includes('FIXTURE_REFERENCE'), 'frozen_reference_material_is_available_for_review')
      const checkbox = document.querySelector('.el-dialog input[type="checkbox"]')
      checkbox.click()
      await wait(() => !button('授权此源码版本').disabled)
      button('授权此源码版本').click()
      await wait(() => writes('/model-consent').length === 1 && !button('审阅并授权源码').disabled)
      const consent = writes('/model-consent')[0]
      require(consent.method === 'PUT' && consent.csrf === 'fixture-csrf', 'consent_uses_existing_put_and_csrf_contract')
      require(consent.body.expectedArtifactSha256 === null && consent.body.artifactSha256 === state.baseline,
        'consent_pins_exact_source_and_previous_permission')
      require(writes('/model-calls').length === 0, 'source_authorization_does_not_trigger_model_request')
      state.connection.enabled = true
      refresh()
      await wait(() => !button('请求模型建议').disabled)
    }
    button('请求模型建议').click()
    await wait(() => button('确认请求'))
    require(writes('/model-calls').length === 0, 'opening_confirmation_does_not_call_model')
    button('确认请求').click()
    if (mode === 'retry' || mode === 'refresh-error') {
      const expectedError = mode === 'retry' ? 'FIXTURE_RESPONSE_LOST' : 'FIXTURE_REFRESH_FAILED'
      await wait(() => document.body.innerText.includes(expectedError))
      require(writes('/model-calls').length === 1, 'request_failure_is_not_automatically_retried')
      if (mode === 'refresh-error') {
        require(document.body.innerText.includes('本次重试保留原请求标识'), 'accepted_request_keeps_key_until_list_confirms_it')
        button('请求模型建议').click()
        await wait(() => button('确认请求'))
      }
      button('确认请求').click()
      await wait(() => writes('/model-calls').length === 2 && !document.body.innerText.includes(expectedError))
      require(writes('/model-calls')[0].body.requestKey === writes('/model-calls')[1].body.requestKey,
        'explicit_retry_reuses_original_idempotency_key')
      require(state.generations === 1, 'lost_response_retry_creates_only_one_fixture_generation')
    }
    await wait(() => button('取消模型请求'))
    const sent = writes('/model-calls')[0].body
    require(sent.autoSubmit === false && sent.expectedFaultSha256 === state.repair.faultSha256
      && sent.expectedConfigurationSha256 === state.connection.configurationSha256,
    'generation_pins_fault_and_configuration_and_never_auto_submits')
    if (mode === 'cancel') {
      button('取消模型请求').click()
      await wait(() => document.querySelector('.model-call')?.innerText.includes('已取消'))
      require(writes('/cancel').length === 1, 'active_model_request_can_be_explicitly_cancelled')
      require(writes('/submit').length === 0, 'cancel_does_not_submit_a_candidate')
    } else {
      complete()
      await wait(() => button('提交模型候选并静态验证'))
      require(writes('/submit').length === 0, 'received_proposal_is_not_submitted_automatically')
      button('提交模型候选并静态验证').click()
      await wait(() => button('隔离复测候选'))
      require(document.querySelector('.candidate').innerText.includes('fixture-call'), 'candidate_keeps_model_call_provenance')
      require(writes('/submit')[0].body.expectedProposalSha256 === state.proposalSha, 'submission_pins_proposal_identity')
      require(writes('/runtime-validation').length === 0, 'model_submission_never_triggers_runtime_execution')
      button('隔离复测候选').click()
      await wait(() => document.querySelector('.candidate')?.innerText.includes('首页恢复观察：已观察到'))
      require(writes('/runtime-validation')[0].body.expectedCandidateSha256 === state.candidateSha,
        'separate_runtime_action_pins_candidate_identity')
      require(document.querySelector('.candidate').innerText.includes('业务与播放未验证'),
        'fixture_runtime_observation_does_not_claim_playback')
    }
    button('撤销源码授权').click()
    await wait(() => state.consent.artifactSha256 === null && !button('审阅并授权源码').disabled)
    require(writes('/model-consent').at(-1).body.artifactSha256 === null, 'source_consent_can_be_revoked')
  }
  require(!window.fixtureInjected, 'source_and_reference_text_are_never_executed')
  require(state.writes.every(row => row.csrf === 'fixture-csrf'), 'all_mutations_keep_csrf')
  require(document.documentElement.scrollWidth <= innerWidth + 1, 'model_repair_window_fits_viewport')
  return { evidenceClass: 'offline_ui_fixture_only', modelCalled: false, sourceExecuted: false,
    nasAccessed: false, published: false, mode, passed: checks.length, checks,
    viewport: { width: innerWidth, height: innerHeight } }
})()
