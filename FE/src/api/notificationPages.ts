import { notificationGet } from './client.ts'
import type { PageResult } from './types'

const PAGE_SIZE = 100

export async function getAllNotificationPages<T>(
  path: string,
  params: Record<string, string | number | boolean | undefined> = {},
): Promise<PageResult<T>> {
  const first = await notificationGet<PageResult<T>>(path, { ...params, page: 0, size: PAGE_SIZE })
  const content = [...first.content]
  for (let page = first.page + 1; page < first.totalPages; page += 1) {
    const next = await notificationGet<PageResult<T>>(path, { ...params, page, size: PAGE_SIZE })
    content.push(...next.content)
  }
  return { ...first, content, hasNext: false }
}
