import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { test } from 'node:test'
import { parse, compileStyle } from '@vue/compiler-sfc'

const root = new URL('../../', import.meta.url)
const read = path => readFileSync(new URL(path, root), 'utf8')

test('maintenance mounts inside the existing AT route without an iframe', () => {
  const { descriptor } = parse(read('src/views/MaintenanceView.vue'))
  assert.match(descriptor.scriptSetup.content, /import MaintenanceWorkbench from/)
  assert.match(descriptor.template.content, /<MaintenanceWorkbench\s*\/>/)
  assert.doesNotMatch(descriptor.template.content, /iframe|src=/)
  assert.match(read('src/router/index.ts'), /path: '\/maintenance'/)
})

test('workbench styles cannot target the host root or body', () => {
  const { descriptor } = parse(read('src/maintenance/MaintenanceWorkbench.vue'))
  assert.ok(descriptor.styles.every(style => style.scoped))
  assert.doesNotMatch(descriptor.styles[0].content, /:root|\bbody\s*\{/)
  const result = compileStyle({ source: descriptor.styles[0].content, filename: 'workbench.vue',
    id: 'data-v-maintenance-fixture', scoped: true })
  assert.deepEqual(result.errors, [])
  assert.match(result.code, /header\[data-v-maintenance-fixture\]/)
})

test('the upstream source has no standalone frontend or production bootstrap', () => {
  const repository = new URL('../', root)
  for (const path of ['plugin-maintenance/web-ui/index.html', 'plugin-maintenance/web-ui/package.json',
    'plugin-maintenance/server/src/main/java/cn/har01d/atmaintenance/MaintenanceApplication.java',
    'plugin-maintenance/server/src/main/java/cn/har01d/atmaintenance/config/SecurityConfiguration.java']) {
    assert.equal(existsSync(new URL(path, repository)), false, path)
  }
  const pom = readFileSync(new URL('pom.xml', repository), 'utf8')
  assert.doesNotMatch(pom, /<directory>plugin-maintenance\/web-ui\/dist<\/directory>/)
  assert.doesNotMatch(pom, /<exclude>static\/\*\*<\/exclude>/)
  assert.match(pom, /<exclude>static\/assets\/\*\*<\/exclude>/)
})

test('archive controls use authenticated downloads instead of unauthenticated navigation', () => {
  for (const name of ['MaintenanceWorkbench', 'SourceImportPanel', 'RepairPanel', 'ReleasePlanPanel', 'MediaProbePanel']) {
    const { descriptor } = parse(read(`src/maintenance/${name}.vue`))
    assert.match(descriptor.scriptSetup.content, /import \{[^}]*downloadFile[^}]*\} from '\.\/api'/)
    assert.doesNotMatch(descriptor.template.content, /:href="`\/api\/plugin-maintenance\//)
    assert.match(descriptor.template.content, /@click="[^"\n]*downloadFile\(/)
  }
})
