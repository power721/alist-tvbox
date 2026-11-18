import { sha256 } from './directory.ts'

export const inventoryLimits = {
  entries: 2000, sources: 500, depth: 8, fileBytes: 1_048_576, totalBytes: 16_777_216
}
export const inventoryFileLimit = 1_048_576
export const inventoryStatuses: Record<string, string> = {
  hashed: '字节已核对', unreadable: '读取失败', symlink: '链接未读取', not_regular: '非普通文件',
  size_blocked: '大小不合规', budget_blocked: '预算不足', changed_or_linked: '变化或多重链接',
  invalid_utf8: '非 UTF-8', binary_or_empty: '空白或二进制'
}
export interface InventoryEntry {
  path: string; kind: 'python' | 'javascript'; bytes: number; sha256: string | null; status: string
}
export interface Inventory {
  schemaVersion: number; kind: string; authority: string; observedAt: string
  owner: { target: 't4_custom' | 'at_static'; containerName: string; containerId: string; imageId: string
    containerDirectory: string; mountDestination: string; mountSource: string; hostDirectory: string }
  scan: { complete: boolean; visitedEntries: number; ignoredEntries: number; sourceBytesRead: number
    limits: typeof inventoryLimits; blockedDirectories: number }
  files: InventoryEntry[]; scannerSha256: string; productionIdentityUnchanged: boolean
  sourceExecuted: false; contentsStored: false; modelCalled: false; published: false
}
function require(condition: unknown): asserts condition {
  if (!condition) throw new Error('NAS 快照结构或边界不符合要求')
}
function object(value: unknown, keys: string[]): asserts value is Record<string, unknown> {
  require(value && typeof value === 'object' && !Array.isArray(value))
  require(Object.keys(value).sort().join('|') === [...keys].sort().join('|'))
}
function integer(value: unknown, max = Number.MAX_SAFE_INTEGER): asserts value is number {
  require(typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= max)
}
function path(value: unknown, absolute: boolean): asserts value is string {
  require(typeof value === 'string' && value.length > 0 && value.length <= 2048
    && !/[\\\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/.test(value)
    && value.startsWith('/') === absolute)
  const parts = (absolute ? value.slice(1) : value).split('/')
  require(parts.every(part => part && part !== '.' && part !== '..'))
}
function hash(value: unknown): asserts value is string {
  require(typeof value === 'string' && /^[a-f0-9]{64}$/.test(value))
}
export function validateInventory(value: unknown): Inventory {
  object(value, ['schemaVersion', 'kind', 'authority', 'observedAt', 'owner', 'scan', 'files',
    'scannerSha256', 'productionIdentityUnchanged', 'sourceExecuted', 'contentsStored', 'modelCalled', 'published'])
  require(value.schemaVersion === 1 && value.kind === 'nas_plugin_directory_inventory'
    && value.authority === 'ssh_host_read_only' && value.productionIdentityUnchanged === true
    && value.sourceExecuted === false && value.contentsStored === false
    && value.modelCalled === false && value.published === false)
  require(typeof value.observedAt === 'string' && value.observedAt.length <= 40
    && /^\d{4}-\d{2}-\d{2}T[\d:.]+(?:Z|\+00:00)$/.test(value.observedAt)
    && Number.isFinite(Date.parse(value.observedAt)))
  hash(value.scannerSha256)
  const owner = value.owner
  object(owner, ['target', 'containerName', 'containerId', 'imageId', 'containerDirectory',
    'mountDestination', 'mountSource', 'hostDirectory'])
  hash(owner.containerId)
  require(typeof owner.imageId === 'string' && /^sha256:[a-f0-9]{64}$/.test(owner.imageId))
  for (const key of ['containerDirectory', 'mountDestination', 'mountSource', 'hostDirectory']) path(owner[key], true)
  require(owner.target === 't4_custom' || owner.target === 'at_static')
  if (owner.target === 't4_custom') {
    require(owner.containerName === 'alist-tvbox-t4-sidecar-t4-sidecar-1'
      && owner.mountDestination === '/data' && (owner.containerDirectory as string).startsWith('/data/'))
  } else {
    require(owner.containerName === 'xiaoya-tvbox' && owner.mountDestination === '/www/static'
      && owner.containerDirectory === '/www/static')
  }
  require(owner.hostDirectory === owner.mountSource
    + (owner.containerDirectory as string).slice((owner.mountDestination as string).length))
  const scan = value.scan
  object(scan, ['complete', 'visitedEntries', 'ignoredEntries', 'sourceBytesRead', 'limits', 'blockedDirectories'])
  require(typeof scan.complete === 'boolean')
  integer(scan.visitedEntries, inventoryLimits.entries)
  integer(scan.ignoredEntries, scan.visitedEntries)
  integer(scan.blockedDirectories, scan.visitedEntries)
  integer(scan.sourceBytesRead, inventoryLimits.totalBytes)
  object(scan.limits, Object.keys(inventoryLimits))
  require(Object.entries(inventoryLimits).every(([key, limit]) => (scan.limits as Record<string, unknown>)[key] === limit))
  require(Array.isArray(value.files) && value.files.length <= inventoryLimits.sources
    && value.files.length + scan.ignoredEntries <= scan.visitedEntries)
  const paths = new Set<string>()
  let hashedBytes = 0
  for (const row of value.files) {
    object(row, ['path', 'kind', 'bytes', 'sha256', 'status'])
    path(row.path, false)
    require(!paths.has(row.path))
    paths.add(row.path)
    require((row.kind === 'python' && row.path.endsWith('.py'))
      || (row.kind === 'javascript' && row.path.endsWith('.js')))
    integer(row.bytes)
    require(typeof row.status === 'string' && Object.hasOwn(inventoryStatuses, row.status))
    if (row.status === 'hashed') {
      hash(row.sha256)
      require(row.bytes > 0 && row.bytes <= inventoryLimits.fileBytes)
      hashedBytes += row.bytes
    } else require(row.sha256 === null)
  }
  require(hashedBytes <= scan.sourceBytesRead)
  return value as unknown as Inventory
}
export async function readInventory(file: { size: number; arrayBuffer(): Promise<ArrayBuffer> }) {
  if (!Number.isSafeInteger(file.size) || file.size <= 0 || file.size > inventoryFileLimit) {
    throw new Error('NAS 快照须非空且不超过 1 MiB')
  }
  const bytes = await file.arrayBuffer()
  require(bytes.byteLength === file.size)
  let value: unknown
  try { value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) }
  catch { throw new Error('NAS 快照不是有效 UTF-8 JSON') }
  return { inventory: validateInventory(value), fileSha256: await sha256(bytes) }
}
