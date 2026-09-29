/** Local-only reader feedback demo. All API calls are mocked; no credentials or deliveries. */
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import { feedbackFixture } from './feedback-fixtures.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const emptyEnvDir = mkdtempSync(join(tmpdir(), 'feedback-preview-'))
const port = 5191
const readers = new Map(['demo-reader-one', 'demo-reader-two'].map((token, index) => [token, {
  ...feedbackFixture(), reportId: 281 + index,
  reportTitle: index ? '두 번째 수신자의 별도 보고서' : feedbackFixture().reportTitle,
  ...(index ? { feedback: [], policies: [] } : {}),
  requests: new Map(), pending: new Map(),
}]))
const json = (res, result, status = 200) => {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json')
  res.setHeader('Cache-Control', 'no-store')
  res.end(JSON.stringify({ isSuccess: status < 400, code: `COMMON${status}`, message: status === 404 ? '요청한 데이터를 찾을 수 없습니다.'
    : status === 409 ? '요청이 현재 상태와 충돌합니다.' : status === 400 ? '잘못된 요청입니다.' : status === 201 ? '생성되었습니다.' : '성공입니다.', result }))
}
const server = await createServer({ root, configFile: false, envDir: emptyEnvDir, cacheDir: join(emptyEnvDir, 'vite-cache'),
  plugins: [react(), { name: 'feedback-demo', configureServer(server) {
    server.middlewares.use(async (req, res, next) => {
      const path = new URL(req.url, 'http://localhost').pathname
      if (!path.startsWith('/api/')) return next()
      if (!path.startsWith('/api/feedback') || req.method !== 'POST') return json(res, null, 404)
      try {
        let raw = ''
        for await (const chunk of req) { raw += chunk; if (raw.length > 16000) return json(res, null, 400) }
        const body = JSON.parse(raw)
        const reader = readers.get(body.token)
        if (!reader) return json(res, null, 404)
        if (path === '/api/feedback/context') {
          for (const [id, pending] of reader.pending) {
            if (Date.now() - pending.started < 4000) continue
            const feedback = reader.feedback.find(item => item.id === id)
            feedback.status = 'COMPLETED'
            feedback.verdict = feedback.category === 'PREFERENCE' ? 'PREFERENCE' : 'INCONCLUSIVE'
            feedback.diagnosis = feedback.category === 'PREFERENCE' ? '개인 관심에 대한 의견으로 검토했습니다. 동의하신 경우에만 이 주제의 개인 기준으로 반영합니다.' : '당시 자료를 살펴봤습니다. 추가 근거 확인이 필요해 사실 오류로 확정하지 않았습니다.'
            if (pending.allowPersonalization) {
              const item = reader.items.find(item => item.itemId === feedback.itemId)
              reader.policies.unshift({ id: id + 1000, topicId: item.topicId, topicName: item.topicName, instruction: '실제 생산·공급 계약과 투자 실행 소식을 우선 참고합니다.',
                version: 1, status: 'ACTIVE', createdAt: new Date().toISOString() })
            }
            reader.pending.delete(id)
          }
          const { requests: _requests, pending: _pending, ...context } = reader
          return json(res, context)
        }
        if (path === '/api/feedback') {
          if (!reader.items.some(item => item.itemId === body.itemId) || !body.comment?.trim() || body.comment.length > 2000
            || !['PREFERENCE', 'TOPIC_MISMATCH', 'SUMMARY_ERROR', 'WRONG_CLUSTER', 'OTHER'].includes(body.category)
            || !body.idempotencyKey || (body.allowPersonalization && body.category !== 'PREFERENCE')) return json(res, null, 400)
          const existing = reader.requests.get(body.idempotencyKey)
          const fingerprint = JSON.stringify({ itemId: body.itemId, category: body.category, comment: body.comment, allowPersonalization: body.allowPersonalization })
          if (existing) return existing.fingerprint === fingerprint ? json(res, reader.feedback.find(item => item.id === existing.id), 201) : json(res, null, 409)
          if (reader.feedback.some(item => item.itemId === body.itemId)) return json(res, null, 409)
          const feedback = { id: 700 + reader.feedback.length, itemId: body.itemId, category: body.category, comment: body.comment,
            status: 'PENDING', verdict: null, diagnosis: null, createdAt: new Date().toISOString() }
          reader.feedback.unshift(feedback)
          reader.requests.set(body.idempotencyKey, { id: feedback.id, fingerprint })
          reader.pending.set(feedback.id, { started: Date.now(), allowPersonalization: body.allowPersonalization })
          return json(res, feedback, 201)
        }
        const match = /^\/api\/feedback\/policies\/(\d+)\/revoke$/.exec(path)
        if (match) {
          const policy = reader.policies.find(item => item.id === Number(match[1]))
          if (!policy) return json(res, null, 404)
          if (policy.status === 'REVOKED') return json(res, policy)
          if (policy.version !== body.version) return json(res, null, 409)
          policy.status = 'REVOKED'; policy.version += 1
          return json(res, policy)
        }
        return json(res, null, 404)
      } catch { return json(res, null, 400) }
    })
  } }], server: { host: '127.0.0.1', port, strictPort: true,
    headers: { 'X-Frame-Options': 'DENY', 'Referrer-Policy': 'no-referrer' } }, logLevel: 'error' })
await server.listen()
console.log(`Feedback demo: http://127.0.0.1:${port}/#/feedback?token=demo-reader-one`)
async function close() { await server.close(); rmSync(emptyEnvDir, { recursive: true, force: true }); process.exit(0) }
process.on('SIGTERM', close)
process.on('SIGINT', close)
