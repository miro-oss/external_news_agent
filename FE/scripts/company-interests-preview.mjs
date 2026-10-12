/** Local-only company criteria demo. Every API request uses synthetic data. */
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import { companyCriteria } from '../tests/fixtures/company-interests.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const emptyEnvDir = mkdtempSync(join(tmpdir(), 'company-interests-preview-'))
const criteria = structuredClone(companyCriteria)
const port = 5192
function json(res, result, status = 200) {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json')
  res.setHeader('Cache-Control', 'no-store')
  res.end(JSON.stringify({ isSuccess: status < 400, code: `COMMON${status}`,
    message: status === 409 ? '상태 충돌이 발생했습니다.' : status === 400 ? '입력값 검증 실패입니다.' : status === 404 ? '리소스를 찾을 수 없습니다.' : '성공입니다.', result }))
}
const server = await createServer({ root, configFile: false, envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'),
  plugins: [react(), { name: 'company-interests-demo', configureServer(server) {
    server.middlewares.use(async (req, res, next) => {
      const path = new URL(req.url, 'http://localhost').pathname
      if (!path.startsWith('/api/')) return next()
      if (path === '/api/news/company-interests' && req.method === 'GET') return json(res, { criteria })
      const match = /^\/api\/news\/company-interests\/(\d+)$/.exec(path)
      if (!match || req.method !== 'PATCH') return json(res, {}, 404)
      try {
        let raw = ''
        for await (const chunk of req) { raw += chunk; if (raw.length > 8000) return json(res, {}, 400) }
        const body = JSON.parse(raw)
        const criterion = criteria.find(item => item.id === Number(match[1]))
        if (!criterion) return json(res, {}, 404)
        if (criterion.version !== body.version) return json(res, {}, 409)
        if (!body.instruction?.trim() || body.instruction.trim().length > 500 || !['DRAFT', 'ACTIVE', 'REVOKED'].includes(body.status)
          || (body.status === 'ACTIVE' && !criterion.topics.some(topic => topic.id === body.topicId))) return json(res, {}, 400)
        Object.assign(criterion, { instruction: body.instruction.trim(), topicId: body.topicId, status: body.status,
          version: criterion.version + 1, updatedAt: new Date().toISOString(), activatedAt: body.status === 'ACTIVE' ? new Date().toISOString() : null, lastApplication: null })
        return json(res, criterion)
      } catch { return json(res, {}, 400) }
    })
  } }], server: { host: '127.0.0.1', port, strictPort: true }, logLevel: 'error' })
await server.listen()
console.log(`Company criteria demo: http://127.0.0.1:${port}/#/company-interests`)
async function close() { await server.close(); rmSync(emptyEnvDir, { recursive: true, force: true }); process.exit(0) }
process.on('SIGTERM', close)
process.on('SIGINT', close)
