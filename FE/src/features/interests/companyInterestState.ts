import type { CompanyInterestCriterion, CompanyInterestUpdate } from '../../api/companyInterests'

export function companyInterestHash(id?: number) {
  return id === undefined ? '#/company-interests' : `#/company-interests?criterionId=${id}`
}
export function companyInterestIdFromHash(hash: string): number | null {
  const value = new URLSearchParams(hash.split('?')[1] ?? '').get('criterionId')
  if (!value || !/^[1-9]\d*$/.test(value)) return null
  const id = Number(value)
  return Number.isSafeInteger(id) ? id : null
}
export function companyInterestStatus(criterion: Pick<CompanyInterestCriterion, 'status' | 'lastApplication'>) {
  if (criterion.status === 'DRAFT') return '기준 확인 대기'
  if (criterion.status === 'REVOKED') return '사용 중지'
  return criterion.lastApplication ? '실제 반영 완료' : '다음 보고서 반영 대기'
}
/** A read started before a successful save must not restore the older criterion version. */
export function reconcileCompanyInterests(incoming: CompanyInterestCriterion[], current: CompanyInterestCriterion[]) {
  const known = new Map(current.map(criterion => [criterion.id, criterion]))
  return incoming.map(criterion => {
    const saved = known.get(criterion.id)
    return saved && saved.version > criterion.version ? saved : criterion
  })
}
export function companyInterestDraftError(criterion: Pick<CompanyInterestCriterion, 'topics'>, draft: CompanyInterestUpdate) {
  if (!draft.instruction.trim() || draft.instruction.trim().length > 500) return '회사 관심 기준을 1~500자로 입력해 주세요.'
  if ((draft.status === 'ACTIVE' && draft.topicId === null)
    || (draft.topicId !== null && !criterion.topics.some(topic => topic.id === draft.topicId))) {
    return '이 의견의 근거와 연결된 주제 하나를 선택해 주세요.'
  }
  return null
}
