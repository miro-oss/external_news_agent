/**
 * Refactor #194 interaction preview. Run from FE with pnpm preview:refactor.
 * Every /api request is intercepted; unknown endpoints fail with QA_BLOCKED.
 * State is in memory. No credentials, backend, collection provider, email, or Telegram are used.
 */
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import react from '@vitejs/plugin-react';
const root = fileURLToPath(new URL('../', import.meta.url));
const portFlag = process.argv.indexOf('--port');
const port = Number(portFlag >= 0 ? process.argv[portFlag + 1] : 5187);
if (!Number.isInteger(port) || port < 1024 || port > 65535)
    throw new Error('--port must be an integer from 1024 to 65535');
// A newly created empty directory prevents Vite from loading the application's .env files.
const emptyEnvDir = mkdtempSync(join(tmpdir(), 'refactor-preview-env-'));
const page = (content) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: content.length ? 1 : 0, hasNext: false });
const sources = [{ id: 1, sourceKind: 'SEARCH', name: 'NAVER', urlTemplate: 'NAVER', country: 'KR', language: 'ko', crawlPolicy: null, robotsStatus: 'allowed', robotsCheckedAt: null, reliabilityScore: 80, active: true }, { id: 2, sourceKind: 'FEED', name: '반도체 뉴스 RSS', urlTemplate: 'https://example.invalid/rss', language: 'ko', active: true }];
const initialTopics = [
    { id: 31, name: '반도체 수출 규제와 공급망', queryText: '반도체 수출', requiredKeywords: ['반도체'], optionalKeywords: ['수출 규제', '공급망'], excludedKeywords: ['채용'], active: true },
    { id: 29, name: 'HBM 시장 동향', queryText: 'HBM 반도체', requiredKeywords: ['HBM'], optionalKeywords: ['SK하이닉스', '삼성전자'], excludedKeywords: ['광고'], active: true },
    { id: 25, name: 'AI 데이터센터 투자', queryText: 'AI 데이터센터', requiredKeywords: ['데이터센터'], optionalKeywords: [], excludedKeywords: ['광고'], active: true },
    { id: 9, name: '중지한 디스플레이 주제', queryText: '디스플레이', requiredKeywords: ['디스플레이'], optionalKeywords: [], excludedKeywords: [], active: false },
].map((t, i) => ({ ...t, batchSize: 100, intervalMinutes: 1440, linkedSourceCount: 2, lastCollectedAt: '2026-09-08T09:20:00+09:00', surgeKeywords: [{ keyword: 'HBM4', issueCount: i === 0 ? 53 : 51, previousIssueCount: 2, deltaIssueCount: i === 0 ? 51 : 49, zScore: 3.1, burst: true }, { keyword: '첨단 패키징', issueCount: 52, previousIssueCount: 2, deltaIssueCount: 50, zScore: 1.7, burst: false }], relatedKeywords: [{ keyword: '마이크론', issueCount: 4, sharePercent: 57 }] }));
const initialProposals = initialTopics.map((t, i) => ({ id: 100 + i, topicId: t.id, topicName: t.name, collectionRunId: 148, status: 'PENDING', summary: i === 0 ? '수출 규제와 공급망 재편에 반복 등장한 키워드를 추가하는 제안입니다.' : '최근 수집한 기사에서 반복 등장한 키워드를 추가합니다.', createdAt: '2026-09-08T09:30:00+09:00', reviewedAt: null, currentKeywords: { requiredKeywords: t.requiredKeywords, optionalKeywords: t.optionalKeywords, excludedKeywords: t.excludedKeywords }, changes: [{ bucket: 'OPTIONAL', action: 'ADD', keyword: i === 0 ? '첨단 패키징' : 'HBM4', reason: '최근 수집에서 반복 등장했으며 현재 주제와 관련성이 높습니다.' }, { bucket: 'EXCLUDED', action: 'ADD', keyword: '채용 공고', reason: '분석 대상에서 채용 안내를 제외합니다.' }] }));
const fixture = JSON.parse(readFileSync(new URL('../tests/fixtures/refactor-report.json', import.meta.url), 'utf8'));
const now = () => new Date().toISOString();
const disabledPolicy = () => ({ enabled: false, run: true, daily: false, channelIds: [], groupIds: [], recipientIds: [] });
const initialChannels = [{ id: 1, channelType: 'EMAIL', name: '이메일', config: {}, maxLength: 20000, active: true, tokenConfigured: false }, { id: 2, channelType: 'TELEGRAM', name: '텔레그램', config: {}, maxLength: 4096, active: true, tokenConfigured: true }, { id: 3, channelType: 'EMAIL', name: '사용 중지한 메일', config: {}, maxLength: 20000, active: false, tokenConfigured: false }];
const initialRecipients = [{ id: 1, name: '김수신', email: 'reader@example.invalid', phone: null, memo: '검증용 수신자', active: true, groupNames: ['반도체 전략팀'], destinations: [{ channelId: 1, channelType: 'EMAIL', address: 'reader@example.invalid', use: true, onboarded: true }, { channelId: 2, channelType: 'TELEGRAM', address: null, use: false, onboarded: false }] }, { id: 2, name: '이구독', email: 'reviewer@example.invalid', phone: null, memo: null, active: true, groupNames: ['반도체 전략팀'], destinations: [{ channelId: 1, channelType: 'EMAIL', address: 'reviewer@example.invalid', use: true, onboarded: true }, { channelId: 2, channelType: 'TELEGRAM', address: 'fixture-chat-2', use: true, onboarded: true }] }, { id: 3, name: '중지한 수신자', email: 'paused@example.invalid', phone: null, memo: null, active: false, groupNames: [], destinations: [] }];
const initialGroups = [{ id: 1, name: '반도체 전략팀', perspective: 'TECHNOLOGY', active: true, memberCount: 2, activeMemberCount: 2, members: [{ recipientId: 1, name: '김수신', active: true }, { recipientId: 2, name: '이구독', active: true }] }, { id: 2, name: '사용 중지한 그룹', perspective: null, active: false, memberCount: 1, activeMemberCount: 0, members: [{ recipientId: 3, name: '중지한 수신자', active: false }] }];
let topics, proposals, channels, recipients, groups, requests, runs, runDeliverySettings, policies, telegram, readiness, audience, plan, autoDeliveries, sendCache;
let telegramLinkError = false;
let showDeliveryLogs = false;
let recipientProfileError = false;
let recipientDeleteError = false;
let loadingDelayMs = 0;
let loadingPath = '/api/';
let usageCalls = 12;
let usageLimit = 100;
let proposalRevision = 0;
const proposalAppliedChanges = new Map();
const proposalKeywordRevisions = new Map();
const deliveryLogFixtures = ['SENT', 'FAILED', 'SKIPPED'].map((status, i) => ({
    id: i + 1, deliveryBatchId: 'fixture-batch', reportId: 17, runId: 148,
    recipientId: i === 1 ? 2 : 1, recipientName: i === 1 ? '이구독' : '김수신',
    channelType: i === 2 ? 'TELEGRAM' : 'EMAIL', address: i === 2 ? 'fixture-chat' : 'reader@example.invalid',
    status, externalMessageId: status === 'SENT' ? 'fixture-message' : null,
    chunkSeq: 1, chunkCount: 1, errorMessage: status === 'FAILED' ? '메일 서버에 연결하지 못했습니다.' : status === 'SKIPPED' ? '수신 설정이 꺼져 있습니다.' : null,
    sentAt: '2026-09-08T12:30:00+09:00',
}));
function reset() { topics = structuredClone(initialTopics); proposals = structuredClone(initialProposals); channels = structuredClone(initialChannels); recipients = structuredClone(initialRecipients); groups = structuredClone(initialGroups); requests = []; runs = []; runDeliverySettings = []; policies = { 31: { enabled: true, run: true, daily: false, groupIds: [1], recipientIds: [], channelIds: [1, 2] } }; telegram = { 1: { status: 'DISCONNECTED', expiresAt: null }, 2: { status: 'CONNECTED', expiresAt: null }, 3: { status: 'DISCONNECTED', expiresAt: null } }; readiness = { mode: 'LOCAL_CAPTURE', configured: false, message: '로컬 검증 모드입니다. 이메일은 실제 수신함으로 전달되지 않습니다.' }; audience = { audience: 'CHIP_MAKER' }; plan = { plan: 'FREE', paidExhaustedAction: 'FALLBACK_FREE', allowRunOverride: true }; autoDeliveries = []; sendCache = {}; resetProposalHistory(); }
const keywordFields = { REQUIRED: 'requiredKeywords', OPTIONAL: 'optionalKeywords', EXCLUDED: 'excludedKeywords' };
const currentTopicKeywords = topic => Object.fromEntries(Object.values(keywordFields).map(field => [field, [...topic[field]]]));
const normalizedKeyword = keyword => keyword.trim().toLowerCase();
const proposalResult = proposal => ({ ...proposal, currentKeywords: currentTopicKeywords(topics.find(topic => topic.id === proposal.topicId)) });
function resetProposalHistory() { proposalRevision = 0; proposalAppliedChanges.clear(); proposalKeywordRevisions.clear(); }
function matchingProposalBaseline(proposal, topic) {
    const normalized = values => [...new Set(values.map(normalizedKeyword))].sort().join('\u0000');
    return Object.values(keywordFields).every(field => normalized(proposal.currentKeywords[field]) === normalized(topic[field]));
}
function reviewProposal(proposal, status) {
    if (proposal.status === status) return proposalResult(proposal);
    const topic = topics.find(item => item.id === proposal.topicId);
    if (status === 'APPROVED') {
        const touched = new Map();
        for (const change of proposal.changes) {
            const field = keywordFields[change.bucket];
            const normalized = normalizedKeyword(change.keyword);
            const key = `${topic.id}:${change.bucket}:${normalized}`;
            if (!touched.has(key)) touched.set(key, { key, field, normalized,
                before: topic[field].find(keyword => normalizedKeyword(keyword) === normalized) ?? null,
                index: topic[field].findIndex(keyword => normalizedKeyword(keyword) === normalized) });
            const present = topic[field].some(keyword => normalizedKeyword(keyword) === normalized);
            if (change.action === 'ADD' && !present) topic[field].push(change.keyword.trim());
            if (change.action === 'REMOVE') topic[field] = topic[field].filter(keyword => normalizedKeyword(keyword) !== normalized);
        }
        const deltas = [];
        for (const entry of touched.values()) {
            const after = topic[entry.field].find(keyword => normalizedKeyword(keyword) === entry.normalized) ?? null;
            const revision = ++proposalRevision;
            proposalKeywordRevisions.set(entry.key, revision);
            if (entry.before !== after) deltas.push({ ...entry, after, revision });
        }
        proposalAppliedChanges.set(proposal.id, deltas);
    }
    else if (proposal.status === 'APPROVED') {
        for (const delta of proposalAppliedChanges.get(proposal.id) ?? []) {
            const current = topic[delta.field].find(keyword => normalizedKeyword(keyword) === delta.normalized) ?? null;
            if (proposalKeywordRevisions.get(delta.key) !== delta.revision || current !== delta.after) continue;
            topic[delta.field] = topic[delta.field].filter(keyword => normalizedKeyword(keyword) !== delta.normalized);
            if (delta.before !== null) topic[delta.field].splice(Math.min(delta.index, topic[delta.field].length), 0, delta.before);
            proposalKeywordRevisions.set(delta.key, ++proposalRevision);
        }
        proposalAppliedChanges.delete(proposal.id);
    }
    proposal.status = status;
    proposal.reviewedAt = now();
    return proposalResult(proposal);
}
function proposalFixtures(variant) {
    for (const initial of initialTopics) {
        const topic = topics.find(item => item.id === initial.id);
        if (topic) Object.assign(topic, currentTopicKeywords(initial));
    }
    if (variant === 'empty') return [];
    const fixtures = structuredClone(initialProposals).map(proposal => ({ ...proposal, topicName: topics.find(topic => topic.id === proposal.topicId)?.name ?? proposal.topicName }));
    if (variant === 'dense') {
        const topic = topics.find(item => item.id === 31);
        Object.assign(topic, {
            requiredKeywords: ['반도체', '메모리'],
            optionalKeywords: ['공급망', '수출 규제', '반도체 장비 투자', '첨단 패키징 후공정 설비 확대 계획'],
            excludedKeywords: ['채용', '광고', '이미 제외한 기업 행사 안내'],
        });
        const recommendations = ['HBM4', 'TSMC', '첨단 패키징', '고대역폭 메모리 공급망 재편',
            '북미 인공지능 데이터센터 투자 확대', '미국 반도체 수출 허가', '생산 라인', '수율',
            '차세대초고밀도반도체후공정장비공급망확대계획장문검증', '전력 반도체', '첨단 공정 경쟁', '일본 소재 수급'];
        const change = (bucket, action, keyword) => ({ bucket, action, keyword, reason: `${bucket} 버킷 ${action} 모의 검증 설명` });
        fixtures[0] = { ...fixtures[0], summary: 'OPTIONAL 버킷과 REQUIRED 조건을 함께 검토하는 모의 설명입니다.',
            currentKeywords: currentTopicKeywords(topic), changes: [
                ...recommendations.map(keyword => change('OPTIONAL', 'ADD', keyword)),
                change('REQUIRED', 'ADD', '미세 공정'),
                change('EXCLUDED', 'ADD', '채용 공고'), change('EXCLUDED', 'ADD', '중복 보도'),
                change('OPTIONAL', 'REMOVE', '반도체 장비 투자'), change('OPTIONAL', 'REMOVE', '공급망'),
                change('EXCLUDED', 'REMOVE', '광고'), change('EXCLUDED', 'REMOVE', '채용'),
            ] };
        fixtures[1].changes = [change('OPTIONAL', 'ADD', 'AI')];
        const emptyTopic = topics.find(item => item.id === 25);
        Object.assign(emptyTopic, { requiredKeywords: [], optionalKeywords: [], excludedKeywords: [] });
        fixtures[2] = { ...fixtures[2], currentKeywords: currentTopicKeywords(emptyTopic), changes: [] };
    }
    return fixtures;
}
reset();
// Saved report fixtures use only example.invalid links and synthetic recipients.
const initialReports = Array.from({ length: 12 }, (_, i) => ({ ...structuredClone(fixture.report), id: 17 - i, title: i === 0 ? fixture.report.title : `HBM 시장 · 이전 ${i}회 리포트`, structuredContent: i === 1 ? null : fixture.report.structuredContent, findingCount: 3, highSensitivityCount: 3, deliveryStatus: 'NOT_SENT' }));
initialReports[1].markdownBody += '\n\n## 기사별 분석\n- 반도체 기업의 신규 투자\n\n## 보고서 제외\n- 중복 기사';
initialReports[1].markdownBody = initialReports[1].markdownBody.replace('수집 또는 분석 제외 사항이 없습니다.', '본문을 확인하지 못한 기사 1건은 분석에서 제외했습니다.');
for (const [index, level, score] of [[2, 'medium', 50], [3, 'low', 20]]) {
    initialReports[index].highSensitivityCount = 0;
    initialReports[index].findings = initialReports[index].findings.map((finding, n) => ({ ...finding, category: n === 0 ? '공급망' : '기업', sensitivity: { ...finding.sensitivity, level, score } }));
}
initialReports[4].collectionContexts = [];
initialReports.push({ ...structuredClone(fixture.report), id: 117, runId: null, sourceRunIds: [42, 43, 44], sourceReportCount: 2, reportScope: 'DAILY', reportDate: '2026-09-08', title: 'HBM 시장 · 2026-09-08 일일 통합 리포트', findingCount: 3, highSensitivityCount: 3, deliveryStatus: 'NOT_SENT' });
initialReports.at(-1).collectionContexts.push({ ...structuredClone(fixture.report.collectionContexts[0]), runId: 43,
    topics: [{ ...structuredClone(fixture.report.collectionContexts[0].topics[0]), topicName: 'AI 인프라', topicId: 2 }] },
    { ...structuredClone(fixture.report.collectionContexts[0]), runId: 44 });
initialReports.push({ ...structuredClone(initialReports.at(-1)), id: 116, reportDate: '2026-09-07', title: '이전 일일 통합 보고서', collectionContexts: [], structuredContent: null,
    markdownBody: fixture.report.markdownBody.replace('수집 또는 분석 제외 사항이 없습니다.', '본문을 확인하지 못한 기사 1건은 분석에서 제외했습니다.'),
    findings: structuredClone(fixture.report.findings).map((finding, index) => ({ ...finding, issue: { ...finding.issue, topicName: index === 1 ? 'AI 인프라' : 'HBM 시장' } })) });
let reports = structuredClone(initialReports);
let reportDeleteError = false;
const json = (res, value, status = 200) => { res.statusCode = status; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(value)); };
const server = await createServer({ root, configFile: false, envDir: emptyEnvDir, plugins: [react(), { name: 'isolated-qa-fixtures', configureServer(server) {
                server.middlewares.use(async (req, res, next) => {
                    const url = new URL(req.url, 'http://127.0.0.1');
                    const path = url.pathname;
                    const method = req.method;
                    if (!path.startsWith('/api/') && !path.startsWith('/__qa/'))
                        return next();
                    try {
                        let raw = '';
                        for await (const chunk of req)
                            raw += chunk;
                        const body = raw ? JSON.parse(raw) : {};
                        if (path.startsWith('/__qa/')) {
                            if (path === '/__qa/reset') {
                                reset();
                                reports = structuredClone(initialReports);
                                reportDeleteError = false;
                                telegramLinkError = false;
                                showDeliveryLogs = false;
                                recipientProfileError = false;
                                recipientDeleteError = false;
                                loadingDelayMs = 0;
                                loadingPath = '/api/';
                                usageCalls = 12;
                                usageLimit = 100;
                                return json(res, { reset: true });
                            }
                            if (path === '/__qa/usage') {
                                const used = Number(body.used ?? url.searchParams.get('used') ?? 12);
                                const limit = Number(body.limit ?? url.searchParams.get('limit') ?? 100);
                                if (![used, limit].every(value => Number.isSafeInteger(value) && value >= 0))
                                    return json(res, { error: 'Use nonnegative integer used and limit values.' }, 400);
                                usageCalls = used;
                                usageLimit = limit;
                                return json(res, { used, limit });
                            }
                            if (path === '/__qa/loading') {
                                const delay = Number(body.delayMs ?? url.searchParams.get('delayMs') ?? 0);
                                const prefix = body.path ?? url.searchParams.get('path') ?? '/api/';
                                if (!Number.isFinite(delay) || delay < 0 || delay > 30000 || !prefix.startsWith('/api/'))
                                    return json(res, { error: 'Use delayMs 0–30000 and an /api/ path prefix.' }, 400);
                                loadingDelayMs = delay;
                                loadingPath = prefix;
                                return json(res, { delayMs: loadingDelayMs, path: loadingPath });
                            }
                            if (path === '/__qa/report-delete-error') {
                                reportDeleteError = body.enabled ?? false;
                                return json(res, { enabled: reportDeleteError });
                            }
                            if (path === '/__qa/telegram-link-error') {
                                telegramLinkError = body.enabled ?? url.searchParams.get('enabled') === 'true';
                                return json(res, { enabled: telegramLinkError });
                            }
                            if (path === '/__qa/delivery-logs') {
                                showDeliveryLogs = body.enabled ?? url.searchParams.get('enabled') === 'true';
                                return json(res, { enabled: showDeliveryLogs });
                            }
                            if (path === '/__qa/recipient-profile-error') {
                                recipientProfileError = body.enabled ?? url.searchParams.get('enabled') === 'true';
                                return json(res, { enabled: recipientProfileError });
                            }
                            if (path === '/__qa/recipient-delete-error') {
                                recipientDeleteError = body.enabled ?? url.searchParams.get('enabled') === 'true';
                                return json(res, { enabled: recipientDeleteError });
                            }
                            if (path === '/__qa/recipients') {
                                const count = Math.max(0, Math.min(500, Number(body.count ?? url.searchParams.get('count') ?? 80)));
                                recipients = count <= 2 ? structuredClone(initialRecipients.filter(r => r.active).slice(0, count)) : [
                                    ...structuredClone(initialRecipients),
                                    ...Array.from({ length: count - 2 }, (_, i) => ({ id: 1000 + i, name: `검증 수신자 ${String(i + 1).padStart(3, '0')}`, email: `reader-${i + 1}@example.invalid`, phone: null, memo: null, active: true, groupNames: [], destinations: [] })),
                                ];
                                return json(res, { activeCount: recipients.filter(r => r.active).length });
                            }
                            if (path === '/__qa/groups') {
                                const count = Number(body.count ?? url.searchParams.get('count') ?? 80);
                                if (!Number.isInteger(count) || count < 0 || count > 1000)
                                    return json(res, { error: 'Use an integer count from 0 to 1000.' }, 400);
                                const members = recipients.filter(r => r.active).slice(0, 2)
                                    .map(r => ({ recipientId: r.id, name: r.name, active: true }));
                                groups = Array.from({ length: count }, (_, i) => ({
                                    ...structuredClone(initialGroups[0]), id: i + 1,
                                    name: i === 0 ? initialGroups[0].name : `검증 수신 그룹 ${String(i + 1).padStart(3, '0')}`,
                                    active: true, memberCount: members.length, activeMemberCount: members.length,
                                    members: structuredClone(members),
                                }));
                                for (const recipient of recipients)
                                    recipient.groupNames = groups.filter(group => group.members.some(member => member.recipientId === recipient.id)).map(group => group.name);
                                return json(res, { activeCount: groups.length });
                            }
                            if (path === '/__qa/proposals') {
                                const variant = body.variant ?? url.searchParams.get('variant') ?? 'default';
                                if (!['default', 'dense', 'mixed', 'empty'].includes(variant))
                                    return json(res, { error: 'Use variant default, dense, mixed, or empty.' }, 400);
                                proposals = proposalFixtures(variant);
                                resetProposalHistory();
                                if (variant === 'mixed') {
                                    reviewProposal(proposals[0], 'APPROVED');
                                    reviewProposal(proposals[1], 'REJECTED');
                                }
                                return json(res, { variant, count: proposals.filter(proposal => topics.some(topic => topic.id === proposal.topicId && topic.active)).length,
                                    proposals: proposals.map(proposal => ({ id: proposal.id, topicId: proposal.topicId, status: proposal.status, changeCount: proposal.changes.length })) });
                            }
                            if (path === '/__qa/findings') {
                                const count = Number(body.count ?? url.searchParams.get('count') ?? 8);
                                const mediumCount = Number(body.mediumCount ?? url.searchParams.get('mediumCount') ?? 0);
                                if (!Number.isInteger(count) || count < 0 || count > 1000
                                    || !Number.isInteger(mediumCount) || mediumCount < 0 || mediumCount > count)
                                    return json(res, { error: 'Use an integer count from 0 to 1000 and mediumCount from 0 to count.' }, 400);
                                reports = reports.map(report => {
                                    const copy = structuredClone(report);
                                    copy.findings = Array.from({ length: count }, (_, i) => {
                                        const finding = structuredClone(fixture.report.findings[i % fixture.report.findings.length]);
                                        const uniqueId = report.id * 10000 + i + 1;
                                        const medium = i >= count - mediumCount;
                                        const title = `검증 이슈 ${String(i + 1).padStart(3, '0')} · ${finding.articleTitle}`;
                                        const summary = `검증 ${i + 1}번째 이슈입니다. ${finding.summary}`;
                                        return {
                                            ...finding, id: 5000000 + uniqueId, articleId: 10000000 + uniqueId,
                                            issueId: 15000000 + uniqueId, articleTitle: title, summary,
                                            canonicalUrl: `https://example.invalid/qa/reports/${report.id}/articles/${i + 1}`,
                                            issue: { ...finding.issue, id: 15000000 + uniqueId, title, summary },
                                            sensitivity: { ...finding.sensitivity, level: medium ? 'medium' : 'high', score: medium ? 50 : 82 },
                                        };
                                    });
                                    copy.findingCount = count;
                                    copy.highSensitivityCount = count - mediumCount;
                                    return copy;
                                });
                                return json(res, { reportCount: reports.length, count, highCount: count - mediumCount, mediumCount });
                            }
                            if (path === '/__qa/requests')
                                return json(res, requests);
                            if (path === '/__qa/state')
                                return json(res, { runs, runDeliverySettings, policies, telegram, readiness, autoDeliveries });
                            if (path === '/__qa/queue/advance') {
                                const run = runs.find(r => r.runId === Number(body.runId || url.searchParams.get('runId'))) || runs.at(-1);
                                if (!run)
                                    return json(res, { error: '먼저 수집 요청을 눌러 주세요.' }, 404);
                                run.status = body.status || url.searchParams.get('status') || (run.status === 'PENDING' ? 'RUNNING' : 'SUCCESS');
                                run.startedAt = run.status === 'PENDING' ? null : run.startedAt || now();
                                run.finishedAt = ['PENDING', 'RUNNING'].includes(run.status) ? null : now();
                                run.reportId = ['SUCCESS', 'PARTIAL'].includes(run.status) ? 17 : null;
                                return json(res, run);
                            }
                            if (path === '/__qa/telegram') {
                                const id = Number(body.recipientId || url.searchParams.get('recipientId') || 1);
                                const status = body.status || url.searchParams.get('status') || 'CONNECTED';
                                telegram[id] = { status, expiresAt: status === 'WAITING' ? new Date(Date.now() + 600000).toISOString() : null };
                                const recipient = recipients.find(r => r.id === id);
                                if (recipient) {
                                    const d = recipient.destinations.find(d => d.channelId === 2);
                                    if (d) {
                                        d.onboarded = status === 'CONNECTED';
                                        d.address = d.onboarded ? `fixture-chat-${id}` : null;
                                        d.use = d.onboarded;
                                    }
                                }
                                return json(res, telegram[id]);
                            }
                            if (path === '/__qa/email') {
                                const mode = body.mode || url.searchParams.get('mode') || 'SMTP';
                                readiness = { mode, configured: mode === 'SMTP', message: mode === 'SMTP' ? '이메일 발송 설정이 준비되어 있습니다.' : '로컬 검증 모드입니다. 이메일은 실제 수신함으로 전달되지 않습니다.' };
                                return json(res, readiness);
                            }
                            if (path === '/__qa/unavailable-policy') {
                                policies[31] = { enabled: true, run: true, daily: false, channelIds: [3, 99], groupIds: [2, 99], recipientIds: [3, 99] };
                                return json(res, policies[31]);
                            }
                            if (path === '/__qa/auto-deliveries') {
                                autoDeliveries = body.length ? body : [{ id: 1, recipientName: '김수신', channelType: 'EMAIL', status: 'FAILED', attempts: 3, message: 'SMTP 연결을 확인해 주세요.' }];
                                return json(res, autoDeliveries);
                            }
                            return json(res, { error: 'Unknown QA endpoint' }, 404);
                        }
                        requests.push({ method, path, query: Object.fromEntries(url.searchParams), body, at: now() });
                        if (method === 'GET' && loadingDelayMs > 0 && path.startsWith(loadingPath))
                            await new Promise(resolve => setTimeout(resolve, loadingDelayMs));
                        let result;
                        let status = 200;
                        let match;
                        if (path === '/api/usage/llm') {
                            const resetAt = '2026-09-09T00:00:00+09:00';
                            result = { currentPlan: plan.plan,
                                free: { dailyCallsUsed: usageCalls, dailyCallsLimit: usageLimit, dailyCallsRemaining: Math.max(0, usageLimit - usageCalls), resetAt },
                                paid: { dailyCreditsUsed: 0, dailyCreditsLimit: 100, dailyCreditsRemaining: 100,
                                    analysisCreditsRemaining: 80, insightCreditsUsed: 0, insightCreditsCap: 20, insightCreditsRemaining: 20,
                                    reportReserve: 20, monthlyCreditsUsed: 0, monthlyCreditsLimit: 3000, monthlyCreditsRemaining: 3000,
                                    dailyResetAt: resetAt, monthlyResetAt: '2026-10-01T00:00:00+09:00' } };
                        }
                        else if (path === '/api/settings/llm-plan') {
                            if (method === 'PUT')
                                plan = { ...plan, ...body };
                            result = plan;
                        }
                        else if (path === '/api/settings/audience') {
                            if (method === 'PUT')
                                audience = body;
                            result = audience;
                        }
                        else if (path === '/api/news/sources')
                            result = page(sources);
                        else if (path === '/api/news/topic-sources')
                            result = { ...page(topics.flatMap(t => sources.map(s => ({ topicId: t.id, topicName: t.name, sourceId: s.id, sourceName: s.name, sourceKind: s.sourceKind, queryText: t.queryText, active: t.active, batchSize: 100, intervalMinutes: 1440, lastCollectedAt: t.lastCollectedAt, lastCollectedCount: 12 })))), combinationCount: topics.length * 2 };
                        else if (path === '/api/news/topics' && method === 'POST') {
                            result = { id: Math.max(...topics.map(t => t.id)) + 1, ...body, active: true, lastCollectedAt: null, linkedSourceCount: 2, sources, surgeKeywords: [], relatedKeywords: [] };
                            topics.unshift(result);
                            status = 201;
                        }
                        else if (path === '/api/news/topics') {
                            const active = url.searchParams.get('active');
                            result = page(topics.filter(t => active === null || t.active === (active === 'true')).sort((a, b) => b.id - a.id));
                        }
                        else if ((match = path.match(/^\/api\/news\/topics\/(\d+)\/activation$/))) {
                            const topic = topics.find(t => t.id === Number(match[1]));
                            topic.active = body.active;
                            result = { id: topic.id, name: topic.name, active: topic.active, nextScheduledAt: null };
                        }
                        else if (path === '/api/news/topics/keyword-proposals') {
                            const status = url.searchParams.get('status');
                            result = page(proposals.filter(p => topics.some(t => t.id === p.topicId && t.active) && (!status || p.status === status)).map(proposalResult));
                        }
                        else if ((match = path.match(/keyword-proposals\/(\d+)\/(approve|reject)$/))) {
                            const proposal = proposals.find(p => p.id === Number(match[1]));
                            if (!proposal) return json(res, { isSuccess: false, code: 'TOPIC404', message: '키워드 제안을 찾을 수 없습니다.', result: null }, 404);
                            const nextStatus = match[2] === 'approve' ? 'APPROVED' : 'REJECTED';
                            if (nextStatus === 'APPROVED' && proposal.status === 'PENDING'
                                && !matchingProposalBaseline(proposal, topics.find(topic => topic.id === proposal.topicId)))
                                return json(res, { isSuccess: false, code: 'TOPIC409', message: '제안 생성 후 주제 키워드가 변경되었습니다. 새 제안을 기다려 주세요.', result: null }, 409);
                            result = reviewProposal(proposal, nextStatus);
                        }
                        else if (path === '/api/news/runs' && method === 'POST') {
                            result = runs.find(r => r.idempotencyKey === body.idempotencyKey);
                            if (!result) {
                                const activeTopicIds = topics.filter(t => t.active).map(t => t.id);
                                const targetTopicIds = [...new Set(body.topicIds || activeTopicIds)].filter(id => activeTopicIds.includes(id));
                                result = { runId: 200 + runs.length, status: 'PENDING', triggerType: 'MANUAL', idempotencyKey: body.idempotencyKey, llmPlan: body.plan || plan.plan, targetTopicIds, targetCombinationCount: targetTopicIds.length * 2, queuedAt: now(), startedAt: null, finishedAt: null, reportId: null };
                                runs.push(result);
                                const delivery = body.delivery === undefined ? null : structuredClone(body.delivery);
                                const updatedTopicIds = delivery?.mode === 'TOPIC' ? [...targetTopicIds] : [];
                                if (updatedTopicIds.length) {
                                    const policy = { enabled: delivery.enabled, run: delivery.run, daily: delivery.daily,
                                        groupIds: delivery.groupIds, recipientIds: delivery.recipientIds, channelIds: delivery.channelIds };
                                    for (const topicId of updatedTopicIds)
                                        policies[topicId] = structuredClone(policy);
                                }
                                // QA-only state records each accepted setting without sending anything or changing the API response.
                                runDeliverySettings.push({ runId: result.runId, targetTopicIds: [...targetTopicIds], delivery, updatedTopicIds });
                            }
                            status = 202;
                        }
                        else if (path === '/api/news/runs') {
                            const status = url.searchParams.get('status');
                            result = page(runs.filter(r => !status || r.status === status).toReversed());
                        }
                        else if ((match = path.match(/^\/api\/news\/runs\/(\d+)$/)))
                            result = runs.find(r => r.runId === Number(match[1]));
                        else if (path === '/api/news/reports') {
                            const scope = url.searchParams.get('reportScope');
                            result = page(reports.filter(r => !scope || r.reportScope === scope));
                        }
                        else if (path === '/api/news/reports/latest')
                            result = reports.find(r => r.reportScope === (url.searchParams.get('reportScope') || 'RUN')) || null;
                        else if ((match = path.match(/^\/api\/news\/reports\/(\d+)$/))) {
                            const id = Number(match[1]);
                            if (method === 'DELETE') {
                                if (reportDeleteError) return json(res, { isSuccess: false, code: 'COMMON500', message: '보고서를 삭제하지 못했습니다. 다시 시도해 주세요.', result: {} }, 500);
                                reports = reports.filter(r => r.id !== id);
                                result = { id, deleted: true };
                            } else {
                                result = reports.find(r => r.id === id);
                                if (!result) return json(res, { isSuccess: false, code: 'REPORT404', message: '보고서를 찾을 수 없습니다.', result: {} }, 404);
                            }
                        }
                        else if (path.startsWith('/api/news/articles/'))
                            result = fixture.article;
                        else if (path.startsWith('/api/news/issues/'))
                            result = fixture.issue;
                        else if (path === '/api/notifications/channels')
                            result = channels;
                        else if ((match = path.match(/^\/api\/notifications\/channels\/(\d+)$/))) {
                            result = channels.find(c => c.id === Number(match[1]));
                            Object.assign(result, body);
                        }
                        else if (path === '/api/notifications/recipients' && method === 'POST') {
                            const id = Math.max(0, ...recipients.map(r => r.id)) + 1;
                            result = { id, ...body, active: true, phone: null, memo: body.memo || null, groupNames: [], destinations: body.destinations.map(d => ({ ...d, channelType: channels.find(c => c.id === d.channelId)?.channelType, onboarded: d.channelId === 1 })) };
                            recipients.push(result);
                        }
                        else if (path === '/api/notifications/recipients')
                            result = page(recipients);
                        else if ((match = path.match(/^\/api\/notifications\/recipients\/(\d+)\/destinations$/)) && method === 'PUT') {
                            const recipient = recipients.find(r => r.id === Number(match[1]));
                            if (!recipient) return json(res, { isSuccess: false, code: 'RECIPIENT404', message: '수신자를 찾을 수 없습니다.', result: {} }, 404);
                            if (body.destinations.some(d => d.use && !d.address?.trim()))
                                return json(res, { isSuccess: false, code: 'RECIPIENT400', message: '수신 주소 없이 해당 채널을 활성화할 수 없습니다.', result: {} }, 400);
                            if (body.destinations.some(d => d.address && recipients.some(r => r.id !== recipient.id && r.destinations.some(existing => existing.channelId === d.channelId && existing.address === d.address.trim()))))
                                return json(res, { isSuccess: false, code: 'RECIPIENT409', message: '이미 등록된 수신 주소입니다.', result: {} }, 409);
                            recipient.destinations = body.destinations.map(d => {
                                const type = channels.find(c => c.id === d.channelId)?.channelType;
                                const address = d.address?.trim() || null;
                                const old = recipient.destinations.find(existing => existing.channelId === d.channelId && existing.address === address);
                                return { ...d, address, channelType: type, onboarded: type === 'EMAIL' || Boolean(old?.onboarded) };
                            });
                            result = { recipientId: recipient.id, destinations: recipient.destinations };
                        }
                        else if ((match = path.match(/^\/api\/notifications\/recipients\/(\d+)$/)) && method === 'PATCH') {
                            if (recipientProfileError) return json(res, { isSuccess: false, code: 'COMMON500', message: '서버 내부 오류가 발생했습니다.', result: {} }, 500);
                            const recipient = recipients.find(r => r.id === Number(match[1]));
                            if (!recipient) return json(res, { isSuccess: false, code: 'RECIPIENT404', message: '수신자를 찾을 수 없습니다.', result: {} }, 404);
                            if (body.email !== undefined && body.email !== null) recipient.email = body.email.trim() || null;
                            result = { id: recipient.id, name: recipient.name, phone: recipient.phone, email: recipient.email, memo: recipient.memo, active: recipient.active };
                        }
                        else if ((match = path.match(/^\/api\/notifications\/recipients\/(\d+)$/)) && method === 'DELETE') {
                            if (recipientDeleteError) return json(res, { isSuccess: false, code: 'COMMON500', message: '수신자를 삭제하지 못했습니다. 다시 시도해 주세요.', result: {} }, 500);
                            const recipient = recipients.find(r => r.id === Number(match[1]));
                            if (!recipient) return json(res, { isSuccess: false, code: 'RECIPIENT404', message: '수신자를 찾을 수 없습니다.', result: {} }, 404);
                            const removedGroups = groups.filter(g => g.members?.some(member => member.recipientId === recipient.id));
                            for (const group of removedGroups) {
                                group.members = group.members.filter(member => member.recipientId !== recipient.id);
                                group.memberCount = group.members.length;
                                group.activeMemberCount = group.members.filter(member => member.active).length;
                            }
                            Object.assign(recipient, { active: false, destinations: [], groupNames: [] });
                            result = { id: recipient.id, active: false, deletedAt: now(), removedGroupCount: removedGroups.length };
                        }
                        else if ((match = path.match(/^\/api\/notifications\/recipients\/(\d+)\/telegram(\/link)?$/))) {
                            const id = Number(match[1]);
                            if (match[2]) {
                                if (telegramLinkError)
                                    return json(res, { isSuccess: false, code: 'COMMON500', message: '서버 내부 오류가 발생했습니다.', result: {} }, 500);
                                const expiresAt = new Date(Date.now() + 600000).toISOString();
                                telegram[id] = { status: 'WAITING', expiresAt };
                                result = { url: `http://127.0.0.1:${port}/__qa/telegram?recipientId=${id}&status=CONNECTED`, expiresAt };
                            }
                            else {
                                if (method === 'DELETE')
                                    telegram[id] = { status: 'DISCONNECTED', expiresAt: null };
                                result = telegram[id] || { status: 'DISCONNECTED', expiresAt: null };
                            }
                        }
                        else if (path === '/api/notifications/groups' && method === 'POST') {
                            if (groups.some(group => group.name === body.name))
                                return json(res, { isSuccess: false, code: 'GROUP409', message: '이미 존재하는 그룹명입니다.', result: {} }, 409);
                            const members = recipients.filter(r => body.recipientIds.includes(r.id));
                            result = { id: Math.max(0, ...groups.map(g => g.id)) + 1, name: body.name, perspective: body.perspective || null, active: true, memberCount: members.length, activeMemberCount: members.filter(r => r.active).length, members: members.map(r => ({ recipientId: r.id, name: r.name, active: r.active })) };
                            groups.push(result);
                            status = 201;
                        }
                        else if (path === '/api/notifications/groups')
                            result = page(groups);
                        else if ((match = path.match(/^\/api\/notifications\/groups\/(\d+)$/)) && method === 'DELETE') {
                            groups = groups.filter(g => g.id !== Number(match[1]));
                            result = null;
                        }
                        else if ((match = path.match(/^\/api\/notifications\/topics\/(\d+)\/delivery-policy$/))) {
                            if (method === 'PUT')
                                policies[match[1]] = body;
                            result = policies[match[1]] || disabledPolicy();
                        }
                        else if (path === '/api/notifications/email-readiness')
                            result = readiness;
                        else if (path === '/api/notifications/delivery-logs') {
                            const entries = (showDeliveryLogs ? deliveryLogFixtures : []).filter(log =>
                                ['reportId', 'channelType', 'status'].every(key => !url.searchParams.get(key) || String(log[key]) === url.searchParams.get(key)));
                            result = { ...page(entries), summary: { sentCount: entries.filter(log => log.status === 'SENT').length, failedCount: entries.filter(log => log.status === 'FAILED').length, skippedCount: entries.filter(log => log.status === 'SKIPPED').length } };
                        }
                        else if (path.endsWith('/auto-deliveries/retry')) {
                            result = { queuedCount: autoDeliveries.filter(d => d.status === 'FAILED').length };
                            autoDeliveries = autoDeliveries.map(d => d.status === 'FAILED' ? { ...d, status: 'PENDING' } : d);
                        }
                        else if (path.endsWith('/auto-deliveries'))
                            result = autoDeliveries;
                        else if ((match = path.match(/^\/api\/notifications\/reports\/(\d+)\/preview$/))) {
                            const channel = channels.find(c => c.id === body.channelId);
                            const text = '<b>HBM 시장 핵심 요약</b>\nHBM4 공급 준비와 첨단 패키징 투자가 이어지고 있습니다.\n양산 일정과 후속 발주를 확인하세요.\n기사 목록 대신 핵심 내용만 짧게 전달합니다.';
                            result = { reportId: Number(match[1]), channelId: channel.id, channelType: channel.channelType, parseMode: 'HTML', maxLength: channel.maxLength, subject: channel.channelType === 'EMAIL' ? 'HBM 시장 · 핵심 요약' : null, chunks: [{ seq: 1, length: text.length, body: text }], chunkCount: 1 };
                        }
                        else if ((match = path.match(/^\/api\/notifications\/reports\/(\d+)\/send$/))) {
                            result = sendCache[body.idempotencyKey];
                            if (!result) {
                                const targetIds = [...new Set([...(body.recipientIds || []), ...groups.filter(g => (body.groupIds || []).includes(g.id)).flatMap(g => g.members.map(m => m.recipientId))])];
                                const results = targetIds.flatMap(id => body.channelIds.map(channelId => ({ recipientId: id, recipientName: recipients.find(r => r.id === id)?.name, channelType: channels.find(c => c.id === channelId)?.channelType, address: 'fixture-only', status: 'SENT', externalMessageId: 'fixture-only', chunkCount: 1, sentAt: now() })));
                                result = { deliveryBatchId: 'fixture-' + Date.now(), reportId: Number(match[1]), requestedAt: now(), targetCount: results.length, sentCount: results.length, failedCount: 0, skippedCount: 0, results };
                                sendCache[body.idempotencyKey] = result;
                            }
                        }
                        else
                            return json(res, { isSuccess: false, code: 'QA_BLOCKED', message: `실제 API 차단: fixture 없는 ${method} ${path}`, result: null }, 501);
                        if (result === undefined)
                            return json(res, { isSuccess: false, code: 'QA404', message: '검증 데이터를 찾지 못했습니다.', result: null }, 404);
                        json(res, { isSuccess: true, code: `COMMON${status}`, message: '성공입니다.', result }, status);
                    }
                    catch (error) {
                        json(res, { isSuccess: false, code: 'QA_ERROR', message: error.message, result: null }, 500);
                    }
                });
            } }], server: { host: '127.0.0.1', port, strictPort: true, proxy: {} }, define: { 'import.meta.env.VITE_API_BASE_URL': JSON.stringify('') } });
async function close() { await server.close(); rmSync(emptyEnvDir, { recursive: true, force: true }); process.exit(0); }
process.once('SIGINT', close);
process.once('SIGTERM', close);
try {
    await server.listen();
}
catch (error) {
    rmSync(emptyEnvDir, { recursive: true, force: true });
    throw error;
}
console.log(`Isolated QA fixture: http://127.0.0.1:${port}/#/settings`);
console.log('All /api requests use in-memory fixtures. No real collection, email, or Telegram delivery is performed.');
