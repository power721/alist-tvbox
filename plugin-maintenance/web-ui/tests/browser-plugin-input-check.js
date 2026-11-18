(async () => {
  const checks = []
  const assert = (condition, label) => { if (!condition) throw Error(label); checks.push(label) }
  const until = async (predicate, label) => {
    for (let i = 0; i < 200; i++) {
      if (predicate()) return
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    throw Error(label)
  }
  const base = '/api/plugin-maintenance'
  const original = window.fetch
  const api = async (path, body) => {
    const csrf = body ? await (await original(base + '/csrf')).json() : null
    const response = await original(base + path, body ? {
      method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify(body)
    } : undefined)
    if (!response.ok) throw Error(`API ${path}: ${response.status}`)
    return response.json()
  }
  const click = (label, root = document) => {
    const button = [...root.querySelectorAll('button')].find(item => item.textContent.trim() === label)
    if (!button || button.disabled) throw Error(`BUTTON_UNAVAILABLE: ${label}`)
    button.click()
  }
  const select = (dialog, text, filename) => {
    const data = new DataTransfer()
    data.items.add(new File([text], filename, { type: 'application/octet-stream' }))
    const input = dialog.querySelector('.plugin-input-picker')
    input.files = data.files
    input.dispatchEvent(new Event('change', { bubbles: true }))
  }
  const visibleDrawer = () => [...document.querySelectorAll('.plugin-input-drawer')]
    .find(value => value.getBoundingClientRect().width > 0)
  const waitClosed = () => until(() => !visibleDrawer(), 'INPUT_DRAWER_NOT_CLOSED')
  await until(() => [...document.querySelectorAll('button')].some(item => item.textContent.trim() === '导入插件包'),
    'APP_NOT_READY')
  const before = await api('/sources')
  const name = `ui-input-${Date.now()}`
  const content = `# ${name}\r\nraise RuntimeError('UI_SOURCE_MUST_NOT_EXECUTE')\r\nclass Spider: pass\r\n`
  const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(content))),
    byte => byte.toString(16).padStart(2, '0')).join('')
  click('导入插件包')
  await until(() => document.querySelector('.plugin-input-picker'), 'INPUT_DIALOG_MISSING')
  const dialog = document.querySelector('.plugin-input-picker').closest('.el-dialog')
  if ([...dialog.querySelectorAll('button')].some(button => button.textContent.trim() === '继续导入')) {
    click('继续导入', dialog)
  }
  select(dialog, content, `${name}.py`)
  await until(() => dialog.innerText.includes(`${name}.py`), 'FILE_NOT_READ')
  const inspect = [...dialog.querySelectorAll('button')].find(button => button.textContent.trim() === '校验输入')
  assert(inspect.disabled, 'explicit_plaintext_authorization_required')
  dialog.querySelector('.el-checkbox__original').click()
  await until(() => !inspect.disabled, 'CONSENT_NOT_APPLIED')
  click('校验输入', dialog)
  await until(() => dialog.innerText.includes(digest), 'REAL_SERVER_PREVIEW_MISSING')
  assert((await api('/sources')).length === before.length, 'preview_does_not_persist_source')
  let lost = false
  const confirmationKeys = []
  window.fetch = async (input, init) => {
    const isConfirm = String(input) === base + '/plugin-inputs' && init?.method === 'POST'
    if (isConfirm) confirmationKeys.push(JSON.parse(init.body).requestKey)
    const response = await original(input, init)
    if (isConfirm && response.ok && !lost) { lost = true; throw TypeError('UI_TEST_LOST_RESPONSE') }
    return response
  }
  try {
    click('确认导入', dialog)
    await until(() => dialog.innerText.includes('UI_TEST_LOST_RESPONSE'), 'LOST_RESPONSE_NOT_EXPOSED')
    assert((await api('/sources')).length === before.length + 1, 'lost_response_still_committed_once')
    assert([...dialog.querySelectorAll('input.el-input__inner')].every(input => input.disabled),
      'uncertain_request_fields_locked')
    click('核对同一保存请求', dialog)
    await until(() => dialog.innerText.includes('已保存 · 未执行'), 'RECONCILIATION_FAILED')
    assert(confirmationKeys.length === 2 && confirmationKeys[0] === confirmationKeys[1],
      'retry_reuses_exact_persisted_request_key')
    assert((await api('/sources')).length === before.length + 1, 'reconciliation_never_duplicates_source')
  } finally { window.fetch = original }
  click('打开受管源', dialog)
  await until(() => document.querySelector('button.source.active')?.innerText.includes(name), 'IMPORTED_SOURCE_NOT_SELECTED')
  const source = (await api('/sources')).find(value => value.name === name)
  const records = await api(`/sources/${source.id}/plugin-inputs`)
  assert(records.length === 1 && records[0].artifactSha256 === digest
    && records[0].evidence.sourceExecuted === false && records[0].evidence.publishEligible === false,
    'persisted_input_identity_has_no_execution_or_publication_claim')
  document.querySelector('.workspace .plugin-input-evidence button').click()
  await until(() => [...document.querySelectorAll('.plugin-input-drawer')].some(value =>
    value.getBoundingClientRect().width > 0 && value.innerText.includes('与导入原文一致')),
    'INPUT_DRAWER_MISSING')
  const drawer = [...document.querySelectorAll('.plugin-input-drawer')].find(value =>
    value.getBoundingClientRect().width > 0)
  assert(drawer.innerText.includes('明文输入，无包签名') && drawer.innerText.includes('未由导入过程验证'),
    'raw_input_is_not_labeled_as_signed_or_business_verified')
  await until(() => {
    const value = drawer.getBoundingClientRect()
    return value.left >= -1 && value.right <= innerWidth + 1
  }, 'DRAWER_TRANSITION_NOT_SETTLED')
  const bounds = drawer.getBoundingClientRect()
  assert(bounds.left >= -1 && bounds.right <= innerWidth + 1
    && document.documentElement.scrollWidth <= innerWidth + 1, 'input_drawer_fits_viewport')
  assert(drawer.querySelector('.el-table__body').getBoundingClientRect().right <= bounds.right + 1,
    'identity_columns_fit_without_horizontal_clipping')
  drawer.querySelector('.el-drawer__close-btn').click()
  await waitClosed()
  click('保存新版本')
  await until(() => [...document.querySelectorAll('.el-dialog')].some(value =>
    value.getBoundingClientRect().width > 0 && value.innerText.includes('保存新的源码版本')
    && [...value.querySelectorAll('button')].some(button =>
      button.textContent.trim() === '保存版本' && !button.disabled)), 'EDITOR_NOT_READY')
  const editor = [...document.querySelectorAll('.el-dialog')].find(value =>
    value.getBoundingClientRect().width > 0 && value.innerText.includes('保存新的源码版本')
    && value.querySelector('textarea'))
  const textarea = editor.querySelector('textarea')
  textarea.value = content + '# local edit\n'
  textarea.dispatchEvent(new Event('input', { bubbles: true }))
  click('保存版本', editor)
  await until(() => document.querySelector('.workspace .plugin-input-evidence')?.innerText.includes('仅对应历史明文'),
    'HISTORICAL_INPUT_NOT_MARKED')
  document.querySelector('.workspace .plugin-input-evidence button').click()
  await until(() => visibleDrawer()?.innerText.includes('原包不覆盖当前版本'), 'EDIT_INHERITED_SIGNATURE_COVERAGE')
  assert(visibleDrawer().innerText.includes(digest), 'original_plaintext_identity_survives_edit')
  visibleDrawer().querySelector('.el-drawer__close-btn').click()
  await waitClosed()
  click('导入插件包')
  click('继续导入', dialog)
  select(dialog, '//@format:secspider/1\npayload.base64:invalid\n', 'invalid-secspider.txt')
  await until(() => dialog.innerText.includes('invalid-secspider.txt'), 'SECOND_FILE_NOT_READ')
  dialog.querySelector('.el-checkbox__original').click()
  await until(() => !inspect.disabled, 'SECOND_CONSENT_NOT_APPLIED')
  click('校验输入', dialog)
  await until(() => dialog.innerText.includes('PLUGIN_PACKAGE_INVALID'), 'INVALID_ENCRYPTED_PACKAGE_ACCEPTED')
  assert((await api('/sources')).length === before.length + 1, 'invalid_encrypted_package_does_not_create_source')
  click('关闭', dialog)
  document.querySelector('.workspace .plugin-input-evidence button').click()
  await until(() => visibleDrawer()?.innerText.includes('原包不覆盖当前版本'), 'FINAL_DRAWER_NOT_READY')
  return { passed: checks.length, checks, width: innerWidth, height: innerHeight,
    sourceId: source.id, artifactSha256: digest, evidenceClass: 'local_http_product_raw_and_invalid_encrypted_input',
    realEncryptedRepositoryVerified: false, sourceExecuted: false }
})()
