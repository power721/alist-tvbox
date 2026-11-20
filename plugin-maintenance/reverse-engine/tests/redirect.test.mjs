import test from 'node:test'
import assert from 'node:assert/strict'
import { chromium } from 'playwright-core'
import { browserProtocol, managedBrowserTargets, browserEnvironment } from '../runner.mjs'
import { sha } from '../analysis.mjs'

test('real Chromium keeps navigation, XHR, OOPIF and popup redirects on managed transport',
  { skip: !process.env.REVERSE_TEST_BROWSER, timeout: 30000 }, async t => {
    const browser = await chromium.launch({ executablePath: process.env.REVERSE_TEST_BROWSER,
      headless: true, chromiumSandbox: true, env: browserEnvironment(), args: [
        '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1', '--site-per-process',
        '--proxy-server=http://127.0.0.1:9', '--proxy-bypass-list=<-loopback>'
      ] })
    let protocol
    try {
      protocol = await browserProtocol(browser)
      const context = await browser.newContext({ serviceWorkers: 'block', acceptDownloads: false })
      const { browserContextIds } = await protocol.send('Target.getBrowserContexts')
      const sent = [], rows = [], failures = [], setups = [], pending = new Set()
      const response = (status, text = '', headers = {}, setCookies = []) => ({
        response: { status, body: Buffer.from(text).toString('base64'), headers, setCookies },
        observation: { responseStatus: status, responseComplete: true, responseContentType: headers['content-type'] }
      })
      const sessions = await managedBrowserTargets(protocol, browserContextIds[0], {
        excludedResourceTypes: ['image', 'media', 'font', 'stylesheet'],
        fail: code => failures.push(code), onRequest: row => rows.push(row),
        onTargetSetup: row => setups.push(row),
        onNetwork() {}, onScript() {}, async onPause(event, session) { await session.send('Debugger.resume') },
        tracked(task) {
          pending.add(task)
          task.catch(error => failures.push(error.message)).finally(() => pending.delete(task))
        },
        async fetch(value) {
          sent.push(value)
          const url = new URL(value.url), html = { 'content-type': 'text/html; charset=utf-8' }
          if (url.pathname.endsWith('-start') || url.pathname === '/start')
            return response(302, '', { location: url.pathname === '/start' ? '/home' : url.pathname.replace('-start', '-end') },
              url.pathname === '/start' ? ['httpOnlyFixture=one; Path=/; Secure; HttpOnly', 'visibleFixture=two; Path=/; Secure'] : [])
          if (url.pathname === '/home') return response(200, `<script>
            window.results = {};
            addEventListener('message', event => results[event.data] = true);
            (async () => {
              const api = await fetch('/api307', {method:'POST', body:new Uint8Array([0,255,128,65])});
              results.post307 = (await api.text()) === 'post-ok' && api.url.endsWith('/api-end');
              const converted = await fetch('/api303', {method:'POST', body:'original-body'});
              results.post303 = (await converted.text()) === 'get-ok' && converted.url.endsWith('/get-end');
            })();
            open('https://popup.other.test/pop-start');
            </script><iframe src="https://child.other.test/frame-start"></iframe>
            <iframe src="/inner-start"></iframe>`, html)
          if (url.pathname === '/api307') return response(307, '', { location: '/api-end' })
          if (url.pathname === '/api303') return response(303, '', { location: '/get-end' })
          if (url.pathname === '/api-end') return response(200, 'post-ok')
          if (url.pathname === '/get-end') return response(200, 'get-ok')
          if (url.pathname === '/frame-end') return response(200,
            `<script>fetch('/frame-api').then(() => parent.postMessage('oopif', '*'))</script>`, html)
          if (url.pathname === '/inner-end') return response(200,
            `<script>parent.postMessage('sameFrame', '*')</script>`, html)
          if (url.pathname === '/pop-end') return response(200,
            `<script>fetch('/pop-api').then(() => opener.postMessage('popup', '*'))</script>`, html)
          return response(200, 'ok')
        }
      })
      const page = await context.newPage()
      await page.goto('https://root.example.test/start', { waitUntil: 'domcontentloaded', timeout: 15000 })
      try {
        await page.waitForFunction(() => ['post307', 'post303', 'oopif', 'sameFrame', 'popup'].every(key => results[key]),
          null, { timeout: 10000 })
      } catch (error) {
        t.diagnostic(JSON.stringify({ failures, setups, sent: sent.map(row => new URL(row.url).pathname),
          results: await page.evaluate(() => results) }))
        throw error
      }
      await Promise.allSettled([...pending])
      assert.equal(page.url(), 'https://root.example.test/home')
      assert.deepEqual(failures, [])
      assert.ok([...sessions.values()].some(session => session.type === 'iframe'))
      assert.equal(sent.filter(row => new URL(row.url).pathname === '/api-end').length, 1)
      const original = sent.find(row => new URL(row.url).pathname === '/api307')
      const redirected = sent.find(row => new URL(row.url).pathname === '/api-end')
      assert.equal(redirected.method, 'POST')
      assert.deepEqual(Buffer.from(redirected.body, 'base64'), Buffer.from([0, 255, 128, 65]))
      assert.equal(original.body, redirected.body)
      const converted = sent.find(row => new URL(row.url).pathname === '/get-end')
      assert.equal(converted.method, 'GET')
      assert.equal(converted.body, '')
      const home = sent.find(row => new URL(row.url).pathname === '/home')
      const cookie = Object.entries(home.headers).find(([key]) => key.toLowerCase() === 'cookie')?.[1] || ''
      assert.ok(cookie.includes('httpOnlyFixture=one') && cookie.includes('visibleFixture=two'))
      assert.ok(!(await page.evaluate(() => document.cookie)).includes('httpOnlyFixture'))
      for (const path of ['/home', '/api-end', '/get-end', '/frame-end', '/inner-end', '/pop-end'])
        assert.ok(rows.some(row => row.http && row.redirectedRequestIdSha256
          && rows.some(previous => previous.requestIdSha256 === row.redirectedRequestIdSha256)
          && sent.some(value => new URL(value.url).pathname === path && row.urlSha256 === sha(value.url))), path)
      assert.ok(rows.every(row => row.http && row.httpAssociation === 'cdp_fetch_request'))
      await context.close()
    } finally {
      await browser.close()
      await protocol?.close()
    }
  })
