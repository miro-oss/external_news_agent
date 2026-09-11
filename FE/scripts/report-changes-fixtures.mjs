// Synthetic saved evidence for the isolated preview. These are not fetched article bodies.
export const reportChangesVariants = ['ready', 'empty', 'unchanged', 'pending', 'running', 'failed', 'no-baseline', 'unavailable', 'not-applicable', 'gap', 'scope', 'undetermined', 'error'];
const messages = {
    PENDING: '보고서 변화 비교를 준비하고 있습니다.',
    RUNNING: '지난 보고서와 달라진 점을 확인하고 있습니다.',
    READY: '지난 보고서와의 비교가 완료되었습니다.',
    FAILED: '변화 비교를 완료하지 못했습니다. 보고서 본문은 확인할 수 있습니다.',
    NO_BASELINE: '비교할 이전 일일 보고서가 없습니다.',
    UNAVAILABLE: '비교 시점의 저장 자료가 없어 변화 비교를 제공할 수 없습니다.',
    NOT_APPLICABLE: '일일 통합 보고서에서 변화 비교를 제공합니다.',
};
function side(id, title, text, historical) {
    const findingId = (historical ? 500 : 600) + id;
    return { issueId: id, topicId: 29, title, summary: text,
        claims: [{ id: `finding-${findingId}-claim-0`, text, evidence: [{ findingId,
            articleId: (historical ? 1000 : 2000) + id, runId: historical ? 41 : 42,
            articleTitle: `${title} · ${historical ? '이전' : '현재'} 보도`, canonicalUrl: `https://example.invalid/news/${findingId}`,
            sentenceIndex: 0, text: `${text} (${historical ? '이전' : '현재'} 보고서에 저장된 원문 문장)` }] }] };
}
export function reportChangesFixture(reportId = 117, variant = 'ready') {
    const title = 'HBM4 양산 일정 구체화';
    const items = [
        { id: 'issue-88', type: 'UPDATED', title, summary: '하반기로 안내됐던 양산 시점이 10월로 구체화되었습니다.',
            previous: side(88, title, 'B사는 하반기 양산을 계획하고 있습니다.', true),
            current: side(88, title, 'B사는 10월 양산을 계획하고 있습니다.', false) },
        { id: 'issue-90', type: 'NEWLY_INCLUDED', title: '첨단 패키징 설비 투자 계획', summary: '첨단 패키징 설비 투자 계획이 이번 보고서에 새로 포함되었습니다.',
            previous: null, current: side(90, '첨단 패키징 설비 투자 계획', 'C사는 첨단 패키징 설비 투자를 검토하고 있습니다.', false) },
        { id: 'issue-91', type: 'REFUTATION', title: '공장 가동 중단 보도에 대한 해명', summary: '가동 중단 보도 이후 회사 측이 일부 라인의 정기 점검이라고 설명했습니다.',
            previous: side(91, '공장 가동 중단 보도', '공장 가동이 중단되었다는 보도가 나왔습니다.', true),
            current: side(91, '회사 측 해명', '회사는 일부 라인에서 정기 점검을 진행한다고 설명했습니다.', false) },
        { id: 'issue-92', type: 'UNDETERMINED', title: '신규 고객 공급 계약', summary: '고객명과 계약 범위를 확인하기 어려워 두 보도가 같은 계약인지 판단을 보류했습니다.',
            previous: side(92, '공급 협의', '해외 고객과 공급 조건을 협의 중이라고 밝혔습니다.', true),
            current: side(93, '공급 계약', '고객사와 공급 계약을 체결했다고 밝혔습니다.', false) },
        { id: 'issue-94', type: 'UNCHANGED', title: '메모리 연구개발 투자', summary: '두 보고서에서 같은 연구개발 투자 계획을 확인했습니다.',
            previous: side(94, '메모리 연구개발 투자', 'D사는 메모리 연구개발 투자를 이어갈 계획입니다.', true),
            current: side(94, '메모리 연구개발 투자', 'D사는 메모리 연구개발 투자를 이어갈 계획입니다.', false) },
    ];
    const status = ['pending', 'running', 'failed', 'no-baseline', 'unavailable', 'not-applicable'].includes(variant)
        ? variant.toUpperCase().replaceAll('-', '_') : 'READY';
    return { reportId, reportDate: '2026-09-08', baseReportId: status === 'NO_BASELINE' ? null : 116,
        baseReportDate: status === 'NO_BASELINE' ? null : variant === 'gap' ? '2026-09-05' : '2026-09-07',
        status, message: messages[status], scopeChanged: variant === 'scope',
        notes: variant === 'scope' ? ['AI 데이터센터 주제가 추가되어 해당 주제의 변화 판단을 보류했습니다.'] : [],
        items: status !== 'READY' || variant === 'empty' ? [] : variant === 'unchanged' ? items.filter(item => item.type === 'UNCHANGED')
            : variant === 'undetermined' ? items.filter(item => item.type === 'UNDETERMINED') : items };
}
