import type { ApiEnvelope } from './types'

/**
 * 서버가 정한 실패 사유를 그대로 들고 다닌다.
 *
 * 화면이 문구를 새로 지어내면 같은 실패가 서버 로그와 화면에서 다르게 불린다. 명세가 코드마다
 * 사용자 문구를 정해 두었으므로(TOPIC409 "이미 존재하는 주제명입니다." 등) 그것을 그대로 쓴다.
 */
export class ApiError extends Error {
  readonly code: string
  readonly status: number | null
  readonly details: unknown

  constructor(code: string, message: string, status?: number, details?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status ?? null
    this.details = details ?? null
  }
}

/**
 * 개발 중에는 Vite dev proxy가 이 접두사를 백엔드로 넘긴다. 백엔드에 CORS 설정을 넣지 않는 이유다 —
 * 운영에서는 같은 오리진으로 서빙되므로 CORS가 애초에 필요 없고, 개발 편의 때문에 서버에
 * 출처 허용을 남겨 두면 그게 그대로 배포된다.
 */
const BASE = '/api/news'

/**
 * 공통 봉투를 여기서 한 번만 푼다.
 *
 * `isSuccess`가 false면 HTTP는 4xx여도 바디에 사유가 들어 있다. 상태 코드만 보고 던지면
 * 그 사유를 잃어버리므로, 바디를 먼저 읽고 코드·메시지를 살려서 던진다.
 */
async function request<T>(path: string, init?: RequestInit, base = BASE): Promise<T> {
  const response = await fetch(`${base}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })

  let envelope: ApiEnvelope<T>
  try {
    envelope = await response.json()
  } catch {
    // 봉투가 아닌 응답(프록시 오류, 502 HTML 등)은 서버가 준 사유가 없다.
    throw new ApiError('NETWORK', `서버에 연결하지 못했습니다. (HTTP ${response.status})`, response.status)
  }

  if (!envelope.isSuccess) {
    throw new ApiError(envelope.code, envelope.message, response.status, envelope.result)
  }
  return envelope.result
}

export function get<T>(path: string, params?: Record<string, string | number | boolean | undefined>) {
  const query = new URLSearchParams()
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== '') {
      query.set(key, String(value))
    }
  })
  const suffix = query.toString()
  return request<T>(suffix ? `${path}?${suffix}` : path)
}

export function post<T>(path: string, body: unknown) {
  return request<T>(path, { method: 'POST', body: JSON.stringify(body) })
}

const NOTIFICATIONS_BASE = '/api/notifications'

export function notificationGet<T>(path: string, params?: Record<string, string | number | boolean | undefined>) {
  const query = new URLSearchParams()
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== '') query.set(key, String(value))
  })
  const suffix = query.toString()
  return request<T>(suffix ? `${path}?${suffix}` : path, undefined, NOTIFICATIONS_BASE)
}

export function notificationPost<T>(path: string, body: unknown) {
  return request<T>(path, { method: 'POST', body: JSON.stringify(body) }, NOTIFICATIONS_BASE)
}

export function notificationPatch<T>(path: string, body: unknown) {
  return request<T>(path, { method: 'PATCH', body: JSON.stringify(body) }, NOTIFICATIONS_BASE)
}

export function notificationPut<T>(path: string, body: unknown) {
  return request<T>(path, { method: 'PUT', body: JSON.stringify(body) }, NOTIFICATIONS_BASE)
}

export function notificationDelete<T>(path: string) {
  return request<T>(path, { method: 'DELETE' }, NOTIFICATIONS_BASE)
}

/** `/api/settings`, `/api/usage`처럼 news 도메인 밖의 제품 API를 호출한다. */
export function apiGet<T>(path: string) {
  return request<T>(path, undefined, '/api')
}

export function apiPut<T>(path: string, body: unknown) {
  return request<T>(path, { method: 'PUT', body: JSON.stringify(body) }, '/api')
}
