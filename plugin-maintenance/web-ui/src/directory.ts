export interface DirectoryFile {
  name: string
  size: number
  webkitRelativePath?: string
  arrayBuffer(): Promise<ArrayBuffer>
}

export interface DirectoryEntry {
  path: string
  filename: string
  name: string
  bytes: number
  sha256: string
  content?: string
  problem?: string
}

export interface DirectoryPreview {
  root: string
  entries: DirectoryEntry[]
  ignored: number
  totalBytes: number
}

export const directoryLimits = { files: 2000, pythonFiles: 500, fileBytes: 1_048_576, totalBytes: 16_777_216 }
const excluded = new Set(['.git', '.venv', 'venv', 'node_modules', '__pycache__', '.idea', '.pytest_cache'])

export async function sha256(bytes: ArrayBuffer): Promise<string> {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
    byte => byte.toString(16).padStart(2, '0')).join('')
}

export async function previewDirectory(files: readonly DirectoryFile[], cancelled = () => false): Promise<DirectoryPreview> {
  if (files.length > directoryLimits.files) throw new Error('目录超过 2000 个文件，请选择更小的子目录')
  const candidates: { file: DirectoryFile; path: string }[] = []
  let ignored = 0
  for (const file of files) {
    const path = file.webkitRelativePath || file.name
    const parts = path.split('/')
    if (!path || path.includes('\\') || path.includes('\0')
        || parts.some(part => !part || part === '.' || part === '..')
        || parts.at(-1) !== file.name) throw new Error('目录包含非法相对路径')
    if (!file.name.endsWith('.py') || parts.some(part => excluded.has(part) || part.startsWith('.'))) {
      ignored++
      continue
    }
    candidates.push({ file, path })
  }
  if (candidates.length > directoryLimits.pythonFiles) throw new Error('Python 文件超过 500 个，请选择子目录')
  const totalBytes = candidates.reduce((sum, item) => sum + item.file.size, 0)
  if (!Number.isSafeInteger(totalBytes) || totalBytes > directoryLimits.totalBytes) {
    throw new Error('Python 文件总大小超过 16 MiB，请选择子目录')
  }
  const paths = new Set<string>()
  const hashes = new Set<string>()
  const entries: DirectoryEntry[] = []
  for (const { file, path } of candidates.sort((a, b) => a.path.localeCompare(b.path))) {
    if (cancelled()) throw new Error('目录预览已取消')
    const entry: DirectoryEntry = {
      path, filename: file.name, name: file.name.slice(0, -3), bytes: file.size, sha256: ''
    }
    entries.push(entry)
    if (paths.has(path)) { entry.problem = '相对路径重复'; continue }
    paths.add(path)
    if (!Number.isSafeInteger(file.size) || file.size <= 0 || file.size > directoryLimits.fileBytes) {
      entry.problem = '文件须非空且不超过 1 MiB'
      continue
    }
    if (file.name.length > 200) { entry.problem = '文件名超过 200 字符'; continue }
    try {
      const bytes = await file.arrayBuffer()
      if (cancelled()) throw new Error('目录预览已取消')
      if (bytes.byteLength !== file.size) throw new Error('读取字节数与文件大小不符')
      entry.sha256 = await sha256(bytes)
      // Keep BOM and CRLF so the server artifact identifies the exact selected bytes.
      const content = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes)
      if (!content.trim() || content.includes('\0')) throw new Error('源码为空或含二进制空字符')
      if (hashes.has(entry.sha256)) { entry.problem = '与目录内另一文件内容相同'; continue }
      hashes.add(entry.sha256)
      entry.content = content
    } catch (error) {
      entry.problem = error instanceof TypeError ? '不是有效 UTF-8 文本'
        : error instanceof Error ? error.message : '文件读取失败'
    }
  }
  return { root: files[0]?.webkitRelativePath?.split('/')[0] || '所选文件', entries, ignored, totalBytes }
}
