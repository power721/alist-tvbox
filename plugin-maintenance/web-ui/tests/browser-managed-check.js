(async () => {
  const checks = []
  const require = (ok, name) => { if (!ok) throw Error(name); checks.push(name) }
  const wait = async predicate => {
    for (let i = 0; i < 100; i++) {
      if (predicate()) return
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    throw Error('UI_WAIT_FAILED')
  }
  const button = document.querySelector('button[aria-label="执行受管源码（无网络）"]')
  require(Boolean(button), 'managed_command_present')
  require(document.body.innerText.includes('MANAGED_EXECUTION_DISABLED'), 'managed_disabled_is_explicit')
  const details = [...document.querySelectorAll('details')].find(item =>
    item.querySelector('summary')?.textContent === '受管执行器预期配置')
  require(Boolean(details), 'expected_policy_is_separate')
  const inspect = document.querySelector('button[aria-label^="拆解 S06 "]')
  if (!inspect) throw Error('S06_INSPECT_MISSING')
  inspect.click()
  await wait(() => document.querySelector('.evidence-drawer')?.getBoundingClientRect().width > 0)
  const drawer = document.querySelector('.evidence-drawer')
  require(drawer.innerText.includes('S06') && drawer.innerText.includes('MANAGED_EXECUTION_DISABLED'),
    'initialization_stays_blocked')
  const rectangle = drawer.getBoundingClientRect()
  require(rectangle.width <= innerWidth + 1 && rectangle.left >= -1
    && document.documentElement.scrollWidth <= innerWidth + 1, 'drawer_and_page_fit_viewport')
  return { passed: checks.length, checks, viewport: { width: innerWidth, height: innerHeight },
    evidenceClass: 'owned_local_disabled_mode_ui', productionCalled: false, realModelCalled: false }
})()
