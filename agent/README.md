# External News Agent

Spring Boot가 내부 HTTP로 호출하는 stateless FastAPI 에이전트입니다. 기사 분석(`/v1/analyze`),
이슈 조사 제안(`/v1/explore`), 수집 키워드 제안(`/v1/keyword-strategy`), run 보고서 작성(`/v1/report`)을
제공하며, 기본 Mock 모드에서는 외부 LLM 없이 결정적인 결과를 반환합니다. 근거 검증
(`/v1/verify-evidence`)은 숫자·날짜·기업명 왜곡을 먼저 차단하고,
단일 문장에서 직접 확인되는 주장은 rule-only로 확정합니다. 복합 주장, 인과·전망 및 의미상
애매한 표현만 provider에 위임하고 근거 연결 상태를 `grounded` / `weak` / `ungrounded`로 반환합니다.

Spring의 상태·실행·예산 책임과 Python의 판단 책임, HTTP 어댑터 교체 절차는
[M13 에이전트 경계](../AGENT_BOUNDARIES.md)에 정리되어 있습니다. `stateless`의 범위,
provider 재시도와 조사 중단 복구의 보장도 이 문서를 기준으로 확인합니다.

## `/v1/keyword-strategy` 수집 키워드 제안 계약

P2-7 수집 전략가는 scheduled run 하나가 끝날 때 해당 주제의 현재 required / optional / excluded
키워드와 이번 실행의 키워드 hit 통계, 최신 기사 관측 최대 20건을 받습니다. 응답은 요약과 최대 12건의
`ADD` / `REMOVE` 제안만 반환합니다. 같은 버킷의 중복 제안, 이미 존재하는 키워드 추가, 존재하지 않는
키워드 제거는 스키마 검증에서 거부됩니다.

Agent는 키워드를 직접 변경하지 않습니다. Spring은 제안을 `PENDING` 상태로 별도 저장하고, 운영자가
승인 API를 호출한 경우에만 주제 키워드에 반영합니다. 반려하거나 Agent 호출·검증·quota 처리가 실패하면
기존 키워드를 그대로 유지합니다. 주제별 검토 대기 제안이 이미 있으면 다음 scheduled run의 새 제안은
건너뜁니다.

```json
{
  "idempotencyKey": "run:42:topic:7:keyword-strategy",
  "plan": "FREE",
  "target": {"type": "TOPIC", "id": 7},
  "topic": {
    "name": "HBM",
    "queryText": "HBM 반도체",
    "requiredKeywords": ["HBM"],
    "optionalKeywords": ["SK하이닉스"],
    "excludedKeywords": ["광고"]
  },
  "run": {
    "id": 42,
    "triggerType": "SCHEDULED",
    "scannedCount": 30,
    "newCount": 8,
    "updatedCount": 2
  },
  "currentKeywordStats": [
    {"bucket": "REQUIRED", "keyword": "HBM", "articleMatchCount": 10}
  ],
  "articles": []
}
```

```json
{
  "summary": "반복 노출된 새 표현을 선택 키워드 후보로 올립니다.",
  "proposals": [
    {
      "bucket": "OPTIONAL",
      "action": "ADD",
      "keyword": "HBM4",
      "reason": "이번 주기 신규 기사에서 반복 등장했습니다."
    }
  ],
  "meta": {
    "provider": "gemini",
    "model": "gemini-2.5-flash",
    "promptVersion": "keyword-strategy.ko.v1",
    "inputTokens": 0,
    "outputTokens": 0,
    "costUsd": 0,
    "credits": 0,
    "mock": false,
    "truncated": false
  }
}
```

## `/v1/explore` 조사 제안 계약

P2-5 조사 Agent는 DB와 외부 수집원을 직접 변경하지 않습니다. 현재 이슈 snapshot, 허용 소스,
이전 step을 받아 `SEARCH_MORE`, `READ_FULLTEXT`, `COMPARE_HISTORY`, `CONCLUDE` 중 정확히 하나를
구조화 출력으로 제안합니다. Spring이 소스 whitelist, 기사 소속, 엔티티, 정규화 질의 hash,
일일 quota를 검증하고 승인된 행동만 실행합니다.

PAID 경로는 P2-4에서 검증한 PydanticAI `2.36.0`의 `NativeOutput(strict=True)`를 사용합니다.
Mindlogic trailing slash와 credits/cost 보존 어댑터를 유지하고 PydanticAI 자체 재시도는 0회로
고정합니다. FREE와 테스트 주입 provider는 기존 구조화 호출 경계를 사용합니다. Agent는 stateless이며
최대 3단계, 이슈/일 1회, 새 근거 없음 및 15% 조사 예산 종료는 Spring 오케스트레이터가 소유합니다.

## `/v1/analyze` 주장 유형 계약

`analyze.ko.v5`부터 모든 bullet은 `claimType`을 반환합니다.

- `FACT`: 숫자·날짜·기업명·부정 반전과 표현 강도를 사실값으로 검증합니다.
- `FORECAST`: 전망·예상·가능성처럼 아직 발생하지 않은 내용이며 그 한정 표현을 유지합니다.
- `OPINION`: 기자·애널리스트·기업 임원·정부 관계자 등의 해석이며 `attributedTo`가 필수입니다.

`OPINION`이 아니면 `attributedTo`는 `null`입니다. Spring은 검증 결과의 `reason`을
`groundingReason`으로 finding에 보존하며 기사·보고서 조회 API와 근거 배지 설명에 전달합니다.

## `/v1/analyze` 자기 검증 계약

P1-7 자기 검증도 새 엔드포인트를 만들지 않고 `/v1/analyze`를 사용합니다. Spring은 이번 실행의
`topicFit + 매체 수 + 최신성` 상위 20% 이슈 가운데 서버 계산 민감도 총점이 70 이상인 결과에만
`selfCritique: true`와 검증된 `previousFinding`을 보냅니다. Agent는 규칙층이 확정하지 못했거나
표현 강도가 한 단계 높거나 교차 출처 충돌과 연결된 주장 중 최대 한 건만 고릅니다.

```json
{
  "idempotencyKey": "run:42:issue:88:self-critique",
  "plan": "FREE",
  "article": {"id": 401, "title": "...", "canonicalUrl": "...", "bodyText": "..."},
  "issueMembers": [],
  "topic": {"name": "반도체 투자"},
  "previousFinding": {
    "summaryKo": "A사가 투자를 승인했다.",
    "sensitivity": {
      "customerMove": {"score": 3, "evidenceSentenceIds": [1]},
      "dealSignal": {"score": null, "evidenceSentenceIds": []},
      "competitorThreat": {"score": 2, "evidenceSentenceIds": [1]},
      "industryShift": {"score": 1, "evidenceSentenceIds": [1]}
    },
    "sections": [{
      "heading": "핵심",
      "bullets": [{
        "text": "A사가 투자를 승인했다.",
        "evidenceSentenceIds": [1],
        "groundedness": "weak",
        "confidence": 0.6,
        "groundingReason": "근거보다 표현이 한 단계 강합니다.",
        "claimType": "FACT",
        "attributedTo": null
      }]
    }],
    "crossSource": {
      "consensus": [], "soleSource": [], "conflicts": [], "missingStakeholders": []
    }
  },
  "selfCritique": true
}
```

검토 질문은 `이 요약에서 원문 문장으로 확인되지 않는 표현은 무엇인가?` 하나로 고정합니다.
선택 주장에 대한 구조화 생성은 한 번이며 schema repair는 수행하지 않습니다. 공통 provider
재시도에 따라 실제 호출 시도는 늘어날 수 있습니다. 새 사실과 새 근거 문장 번호는 추가할 수 없습니다.
자기 검증은 선택된 bullet 한 건만 유지·수정·기각하며 `summaryKo`는 최초 검증 값을 그대로 보존합니다.
규칙으로 이미 확정된 경우 `self-critique.rules.v1` 응답을 비용 0으로 반환합니다. 실패하거나 quota가
부족하면 Spring은 최초 근거 검증 결과를 유지하고 `SELF_CRITIQUE / ISSUE` 감사 행과 경고를 남깁니다.

## `/v1/verify-evidence` 배치 계약

P1-5부터 기사 하나의 검증 대상 bullet을 `claims[]` 한 요청으로 묶습니다. 분석 응답은 최대 16개
section × 3개 bullet로 제한되어 배치 상한 50건 안에 들어옵니다. `claimId`는 요청 안에서
유일하며 응답 `results[]`가 같은 ID를 정확히 한 번씩 반환합니다. 규칙으로 확정하지 못한 주장만
한 번의 provider 구조화 출력에 묶으므로 기사당 검증 HTTP 요청은 최대 1회입니다. 비LLM 결과,
검증 대상 없음 또는 quota 부족이면 HTTP 요청 없이 처리합니다. 다만 schema repair나
provider 재시도가 발생하면 실제 provider 시도는 늘어날 수 있습니다. 층별 책임은
[예산·재시도·실패 경계](../AGENT_BOUNDARIES.md#5-예산재시도실패-책임)를 따릅니다.

```json
{
  "idempotencyKey": "run:42:article:401:evidence",
  "plan": "FREE",
  "claims": [
    {
      "claimId": "0:0",
      "claim": "삼성전자는 평택 투자를 결정했다.",
      "claimType": "FACT",
      "attributedTo": null,
      "sentences": [
        {"id": 1, "text": "삼성전자는 평택 투자를 검토 중이다."}
      ]
    }
  ]
}
```

```json
{
  "results": [
    {
      "claimId": "0:0",
      "status": "ungrounded",
      "acceptedSentenceIds": [],
      "reason": "근거는 '검토' 단계인데 주장은 '결정' 단계입니다."
    }
  ],
  "meta": {
    "provider": "gemini",
    "model": "evidence-rules-v3",
    "promptVersion": "evidence.rules.v3",
    "inputTokens": 0,
    "outputTokens": 0,
    "costUsd": 0,
    "credits": 0,
    "mock": false,
    "truncated": false
  }
}
```

입력 상한은 claim별 `AGENT_EVIDENCE_MAX_CLAIM_CHARS`와
`AGENT_EVIDENCE_MAX_SENTENCES`, 요청 전체 `AGENT_EVIDENCE_MAX_TOTAL_CHARS`를 적용합니다.
`FACT`는 전체 사실·강도 검증, `FORECAST`는 전망 한정 표현 유지, `OPINION`은 발화 주체 귀속을
검증합니다.

`meta.outputTokens`는 provider 과금 기준 출력 토큰입니다. Gemini는 화면에 반환된 candidate
토큰과 내부 thinking 토큰을 합산하고, Mindlogic Claude는 completion 토큰을 사용합니다.

## `/v1/analyze` 교차 출처 계약

`analyze.ko.v4`부터 대표 기사 요청은 같은 이슈의 다른 기사 제목·요약·매체를 `issueMembers`로
최대 10건까지 함께 받을 수 있습니다. 대표와 멤버 모두 교차 비교에는 제목·요약만 사용하고 본문은
넣지 않습니다. 배열이 비어 있으면 기존 기사 단독 분석과 같으며 `crossSource`와
`promoteCandidates`도 빈 값입니다.

```json
{
  "idempotencyKey": "run:42:article:401",
  "plan": "FREE",
  "article": {
    "id": 401,
    "title": "A사 투자 규모 3조원",
    "canonicalUrl": "https://example.com/401",
    "language": "ko",
    "publishedAt": "2026-08-31T09:00:00+09:00",
    "bodyText": "..."
  },
  "issueMembers": [
    {
      "id": 412,
      "title": "A사 투자 규모 5조원",
      "summary": "투자 규모를 5조원으로 보도했다.",
      "publisher": "다른경제"
    }
  ],
  "topic": {
    "name": "반도체 투자",
    "queryText": "반도체 투자",
    "requiredKeywords": [],
    "optionalKeywords": [],
    "excludedKeywords": []
  },
  "previousFinding": null
}
```

응답은 기존 분석 필드와 함께 다음 값을 반환합니다.

```json
{
  "crossSource": {
    "consensus": ["A사가 신규 투자를 준비하고 있다."],
    "soleSource": [],
    "conflicts": [
      {"articleIds": [401, 412], "text": "투자 규모가 3조원과 5조원으로 갈린다."}
    ],
    "missingStakeholders": ["A사 공식 입장"]
  },
  "promoteCandidates": [412],
  "memberStances": [
    {"articleId": 412, "stance": "DISPUTES", "confidence": 0.85}
  ]
}
```

- `crossSource`는 멤버 제목·요약 수준의 관측이므로 근거 문장 번호와
  `factual_mismatches`를 적용하지 않습니다.
- `promoteCandidates`는 `conflicts`에 포함되고, provider 호출 전에 숫자·기업명·부정어 차이를
  규칙으로 확인한 멤버만 포함합니다. 최대 1건입니다.
- `memberStances`는 provider 출력이 아니라 같은 사전 컷에서 만든 결정적 RULE 후보입니다.
  Spring은 모든 후보를 저장한 뒤 대표 기사와 실제 승격 분석에 성공한 기사만 `stanceSource=LLM`으로
  덮어씁니다.
- 승격 요청도 `/v1/analyze`를 사용하지만 재귀 승격은 하지 않습니다. Spring이 이슈당 최대 1건만
  선택하며 추가 호출의 `credits`와 `costUsd`를 별도 `agent_runs` 행에 기록합니다.

```bash
uv sync --frozen
AGENT_SHARED_SECRET=local-dev-agent-token uv run uvicorn app.main:app \
  --host 127.0.0.1 --port 8088
```

P3-1 일일 통합 보고서도 `/v1/report`를 사용합니다. 기존 RUN 입력은 그대로 유효하며,
DAILY는 `run` 컨텍스트에 다음 범위를 명시합니다. `startedAt` 포함·`finishedAt` 미포함인 한국 시간
하루를 나타내며 `id`는 수집 run ID를 가장하지 않도록 null입니다.

```json
{
  "id": null,
  "startedAt": "2026-09-01T00:00:00+09:00",
  "finishedAt": "2026-09-02T00:00:00+09:00",
  "topics": ["HBM"],
  "reportScope": "DAILY",
  "reportId": 410,
  "reportDate": "2026-09-01"
}
```

Spring이 이슈 중복 제거·최신 근거·상위 N개 선정을 소유하고 finding별 최대 3개 검증 주장을 넘깁니다.
Agent는 기존 `report.ko.v1.4` 프롬프트와 최종 검증을 재사용하며 일일 제목은 `reportDate`로 정합니다.
RUN과 DAILY 식별 필드가 섞이면 입력을 거절합니다. 감사/쿼터 키는 `daily-report:{reportId}`이고,
감사 target은 `REPORT`, `collection_run_id`는 null입니다. 실제 측정과 운영 동작은
[일일 보고서 실측·재현 문서](../BE/DAILY_REPORTS.md)를 참고하세요.

보고서는 기사 1건 분석과 별도로 `AGENT_REPORT_MAX_OUTPUT_TOKENS`(기본 8192)와
`AGENT_REPORT_PROVIDER_TIMEOUT_SECONDS`(기본 120초)를 사용합니다. 한 요청에는 우선순위가 높은
LLM finding을 최대 50건까지 받습니다.

관점 인사이트는 최대 4개 관점을 한 번에 생성하므로
`AGENT_INSIGHT_MAX_OUTPUT_TOKENS`(기본 8192)와
`AGENT_INSIGHT_PROVIDER_TIMEOUT_SECONDS`(기본 60초)를 별도로 사용합니다. Spring의
`AGENT_INSIGHT_TIMEOUT` 기본값은 네트워크·직렬화 여유를 포함해 75초입니다.

Mock 근거 검증의 어휘 겹침 임계값은 `AGENT_EVIDENCE_GROUNDED_OVERLAP`(기본 0.6)과
`AGENT_EVIDENCE_WEAK_OVERLAP`(기본 0.2)로 조정할 수 있습니다.
실제 모드의 rule-only `grounded` 판정은 단일 문장 기준으로 최소 0.8 overlap을 요구하며,
설정된 grounded 임계값이 더 높으면 그 값을 따릅니다. 명시적인 한영 동치 관계는 기업·수치·기술
앵커가 함께 일치할 때만 rule-only로 확정합니다.
근거 검증 입력 상한은 `AGENT_EVIDENCE_MAX_CLAIM_CHARS`(기본 2,000),
`AGENT_EVIDENCE_MAX_SENTENCES`(기본 50), `AGENT_EVIDENCE_MAX_TOTAL_CHARS`(기본 40,000)입니다.

실제 provider 호출은 공통 circuit breaker와 동시성 guard를 통과합니다. 기본값은 동시 호출 4건,
연속 실패 3회 시 30초 open이며 모두 환경변수(`AGENT_PROVIDER_CONCURRENCY`,
`AGENT_CIRCUIT_FAILURE_THRESHOLD`, `AGENT_CIRCUIT_COOLDOWN_SECONDS`)로 조정합니다. Mindlogic 응답에
credits 사용량이 없으면 `MINDLOGIC_CREDITS_PER_REQUEST`(기본 1)를 보수적인 환산값으로 사용합니다.

검증 명령은 다음과 같습니다.

```bash
uv run ruff check .
uv run pytest
```

## Golden eval

`app/eval/golden/semiconductor.v1.json`은 한국어·영어 반도체 기사 24건과
`analyze.ko.v6+perspective.ko.v1+sensitivity.ko.v2` replay 출력 및 관점 정답을 담습니다. 수치 오기,
기업명 바꿔치기, 부정 반전, 영문 요약은 기존 `expectedFailures` 4건으로 보존합니다.
`claims.ko.v1.json`은 숫자 불일치·부정 반전·기업명 바꿔치기·강도 과장·원문에 없는 주장 5유형을
각 3쌍씩 담으며, 같은 근거에 invalid claim과 패러프레이즈한 valid positive control을 함께 둡니다.
규칙 대조군은 분석 데이터셋과 분리되어 live checkpoint의 기사 fixture 지문에 영향을 주지 않습니다.
`report.ko.v1.4.json`은 finding과 독립된 버전 보고서 fixture이며 원래 ungrounded였던 보고서 주장이
최종 재검증에서 근거 문장으로 대체되는 것까지 확인합니다.

```bash
uv run python -m app.eval --profile replay \
  --compare app/eval/golden/analyze.ko.v6.baseline.json
```

replay의 `perspectiveTagAccuracy` 96/96은 모델 품질이 아니라 fixture 출력과
`expectedAudiences` 라벨의 일관성을 확인하는 가드입니다. 실제 provider의 관점 태깅 품질은 live
프로필 결과에서 판단합니다.

replay는 외부 API 없이 실제 스키마·문장 분할·사실값 검증·보고서 claim scorer를 실행하는
**계약/규칙 회귀 하네스**입니다. 저장된 출력을 재생하므로 프롬프트 생성 품질을 측정하지는 않습니다.
기본 CI는 replay 기준선만 사용하며 메타데이터, 런타임 설정, 지표 또는 평가 커버리지가 회귀하면
실패합니다.

live 프로필은 실제 provider 인증 정보와 비용 승인이 필요하다. replay와 CI는 저장소에 인증 정보를
보관하거나 읽지 않고, live 프로필만 실행 환경의 provider 인증 변수를 읽는다. P2-2의
`analyze.ko.v6+perspective.ko.v1+sensitivity.ko.v2` 기준선은 replay로 재생성을 확인한다.

- schema pass rate: 분석 24건과 보고서 1건의 계약 검증 통과율
- grounded rate: 분석 bullet 중 `grounded` 판정 비율 (`weak`은 포함하지 않음)
- report weak/unsupported claim count: 보고서 주장을 연결된 finding 요약·핵심 포인트와 기존 A4
  규칙으로 검증했을 때 각각 `weak`/`ungrounded`인 개수. executive summary는 전체 finding을 합치지
  않고 가장 잘 맞는 단일 finding으로 판정
- Korean summary pass rate: 한글 5자 이상이며 한글·영문 문자 중 한글 비율이 50% 이상인 요약 비율
- summary length P50/P95/max: 분석 요약의 글자 수 분포. P95는 120자 이하여야 함
- high sensitivity evidence rate: high 민감도 판정 중 가용 축이 둘 이상이고, 각 축의 유효한 근거 문장이
  하나 이상의 grounded/weak bullet에도 연결된 비율
- perspective tag accuracy: 기사별 정답 관점과 `medium`/`high`로 태깅된 관점의 4×24 일치율
- evidence provider call reduction rate: `ungrounded`로 선차단된 bullet을 제외하고 근거 검증이 필요한
  bullet 중 rule-only로 확정돼 provider 호출을 생략할 수 있는 비율. replay 기준선은 21건 중 11건을
  rule-only로 처리해 예상 provider 호출을 10건으로 줄임
- false pass rate: invalid claim 15건 중 실서비스의 결정 규칙
  (`assess_with_decisive_rules`)이 `grounded`/`weak`로 수용한 비율. provider 위임은 판정 전이므로
  통과로 세지 않습니다. P1-5 기준선은 강도 과장 2건을 차단해 0/15입니다.
- false reject count: 패러프레이즈한 valid positive control 15건 중 결정 규칙이 `ungrounded`로
  선차단한 건수. provider 위임은 오탈락이 아니며 P1-5 기준선은 0건
- claim control provider required count: 결정 규칙이 확정하지 않아 provider 판정이 필요한 대조군
  개수. P1-5 기준선은 9건입니다.

실제 프롬프트 품질은 provider 환경변수를 설정한 뒤 live 프로필로 수동 평가합니다. 결과에는 모델 id,
근거 임계값, 문장 상한, schema repair 횟수와 출력 토큰 상한이 기록됩니다. 전체 골든셋 분석 24회와
보고서 1회 호출이 발생하므로 사용량을 확인한 뒤 실행해야 합니다.

FREE profile은 기본 30초, PAID profile은 기본 1초의 provider 호출 간격을 둡니다. 분당 한도 등 즉시
회복 가능한 429 응답은 provider 응답의 retry delay 또는 15~60초 exponential backoff를 사용해 최대
5회 재시도합니다. `PerDay` 일일 quota 소진은 대기로 회복되지 않으므로 첫 실패에서 즉시 중단하며,
429를 provider 장애 circuit에 누적하지 않습니다. 간격과 재시도 정책은 CLI 옵션으로 결과의
`config.livePolicy`에 함께 기록됩니다.

성공한 분석은 checkpoint에 즉시 저장할 수 있습니다. 중간 실패 결과는 `complete: false`와 exit 1로
표시되며 보고서를 생성하지 않습니다. 429 재시도를 모두 소진하거나 인증·요청 오류가 확인되면 남은
호출을 중단해 quota를 낭비하지 않고, 이후 `--resume`에서 미완료 case부터 다시 시작합니다.

```bash
uv run python -m app.eval --profile live --plan FREE \
  --checkpoint live-v1-free.checkpoint.json \
  --output live-v1-free.json
```

중단된 동일 실행은 dataset, prompt, plan, model, 판정 설정이 같을 때만 이어서 실행됩니다. pacing과
재시도 정책은 quota 상황에 맞춰 더 보수적으로 바꿀 수 있으며, 이미 성공한 분석은 provider를 다시
호출하지 않습니다.

```bash
uv run python -m app.eval --profile live --plan FREE \
  --checkpoint live-v1-free.checkpoint.json --resume \
  --output live-v1-free.json
```

프로젝트의 실제 한도에 맞춰 간격을 바꿔야 한다면 `--request-interval-seconds`를 사용합니다. 재개 시에도
pacing과 재시도 정책은 바꿀 수 있습니다.

동일 데이터셋·모델·런타임 설정에서 프롬프트 버전만 의도적으로 바꿔 비교할 때는
명시적 override를 사용합니다. 그 외 dataset/profile/plan/config 불일치는 비교 오류로 중단됩니다.

```bash
uv run python -m app.eval --profile live --plan FREE \
  --request-interval-seconds 30 \
  --compare live-v1-result.json --allow-prompt-version-change \
  --output live-v2-result.json
```

실제 시크릿이나 provider API 키는 파일에 저장하지 않고 환경변수로만 전달합니다.
