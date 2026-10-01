// Synthetic report-level insight responses for offline UI/API verification.
const audienceText = {
  CHIP_MAKER: ['반도체 제조사', '생산 준비와 고객 인증을 함께 확인해야 합니다.', '패키징 준비가 생산 확대 일정에 연결될 수 있습니다.', '제품별 공급 계획과 고객 인증 진행 상황'],
  EQUIPMENT_MAKER: ['장비·소재사', '패키징 공정 준비와 투자 집행 여부가 우선 확인 대상입니다.', '공정 준비가 실제 설비 투자로 이어질 때 장비 수요를 검토할 수 있습니다.', '설비 발주·공정 인증의 공식 발표'],
  IT_INFRA: ['IT 인프라', '공급 준비가 실제 도입 가능 시점에 연결되는지 확인해야 합니다.', '공급 확대가 완료될 경우 인프라 조달 일정에 영향을 줄 수 있습니다.', '공급 가능 시점과 시스템 검증 결과'],
  MARKET_INVESTOR: ['시장·투자자', '매출이나 수익성으로 연결되는 근거는 제한적이므로 실행 여부를 먼저 확인해야 합니다.', '공급 준비가 실제 매출로 이어지는지는 고객 인증과 공급 계약의 확인이 필요합니다.', '공급 계약과 실적 발표의 실제 매출 기여'],
};
export const reportInsightVariants = ['ready', 'missing', 'empty', 'unavailable', 'disabled', 'inflight', 'quota', 'error', 'lookup-error', 'no-evidence'];
export function reportInsightFixture(report, audience = 'CHIP_MAKER', variant = 'ready') {
  const [label, overview, implication, indicator] = audienceText[audience];
  const facts = (report.findings ?? []).flatMap(finding => (finding.keyPoints ?? []).flatMap((point, index) => {
    if (typeof point === 'string' || point.groundedness !== 'grounded' || !point.evidence?.length) return [];
    return [{ id: `${finding.id}:${index}`, text: point.text, claimType: point.claimType ?? 'FACT', attributedTo: point.attributedTo ?? null,
      findingId: finding.id, articleId: finding.articleId, evidenceSentenceIds: [...point.evidence], groundedness: 'grounded' }];
  }));
  const basis = facts.map(fact => fact.id);
  const issues = (report.findings ?? []).map((finding, index) => ({ findingId: finding.id,
    importance: audience === 'MARKET_INVESTOR' ? 'low' : variant === 'unavailable' ? 'unavailable' : index === 0 ? 'high' : index === 1 ? 'medium' : 'low',
    reason: audience === 'MARKET_INVESTOR' ? '매출·수익성 변화와 직접 연결되는 근거를 확인하지 못했습니다.'
      : index === 0 ? `${label}의 준비 일정에 연결되는 근거여서 먼저 확인할 필요가 있습니다. ${overview}`
        : '업무 판단에 참고할 근거가 있으나 실제 집행과 적용 범위를 추가로 확인해야 합니다.',
    basisClaimIds: facts.filter(fact => fact.findingId === finding.id).map(fact => fact.id),
    axes: { directness: variant === 'unavailable' ? null : audience === 'MARKET_INVESTOR' ? 0 : Math.max(1, 3 - index),
      impact: variant === 'unavailable' ? null : Math.max(1, 3 - index), urgency: index === 2 ? null : 2 - index, novelty: null },
    rank: index + 1,
  })).slice(0, 5);
  const empty = variant === 'empty';
  return { cached: true, reportId: report.id, inputHash: 'a'.repeat(64), promptVersion: 'report-insight.ko.v1', rubricVersion: 'report-importance.v1',
    inputFindingCount: report.findings?.length ?? 0, insights: [{ audience,
      headline: empty ? '이 관점과 직접 연결되는 검증된 분석이 없습니다.' : `${label} 관점에서는 실행 일정과 확인 가능한 근거를 우선 살펴봐야 합니다.`,
      importance: empty || variant === 'unavailable' ? 'unavailable' : audience === 'MARKET_INVESTOR' ? 'low' : 'high',
      overview: empty || !basis.length ? [] : [{ text: overview, basisClaimIds: basis.slice(0, 2), assumption: '보고서에 언급된 준비가 실제 일정과 연결된다는 조건에서 의미가 있습니다.' }],
      issues: empty ? [] : issues, facts: empty ? [] : facts,
      implications: empty || !basis.length ? [] : [{ text: implication, mechanism: '생산 준비가 인증·공급 일정에 연결되고, 이 일정이 관련 업무의 준비 시점을 바꿀 수 있습니다.',
        basisClaimIds: basis.slice(0, 2), assumption: '공식 일정과 인증 결과로 실제 실행이 확인되어야 합니다.', falsifiedBy: '준비 일정의 지연이나 적용 취소가 확인되면 이 해석을 다시 검토합니다.' }],
      watchItems: empty || !basis.length ? [] : [{ topic: '계획의 실제 실행 여부', indicator, trigger: '공식 발표에서 실행 일정이나 적용 범위가 확정되면 우선순위를 다시 판단합니다.', basisClaimIds: basis.slice(0, 2) }],
      llmProvider: 'offline-fixture', llmModel: 'synthetic-demo', createdAt: '2026-09-30T09:00:00+09:00' }] };
}
