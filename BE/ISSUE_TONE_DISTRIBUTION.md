# P3-2 견해 포함 기사 논조 분포 — #158

`GET /api/news/issues/{issueId}`의 `toneDistribution`을 보고서 이슈 상세에 표시한다.
Notion `API 명세서` → `이슈 상세 조회`의 P3-2 계약과 Swagger를 함께 반영했다.

## 집계 기준

1. 현재 이슈 멤버 기사마다 최신 finding(ID 최대값)을 조회한다. 대표 분석을 멤버에 상속하지 않는다.
2. 같은 `contentGroupId`에서는 최신 finding 1건만 선택한다. 중복군이 없는 기사는 개별 후보다.
3. 최신 선택 후 LLM/REUSED 분석 중 발화 주체·본문·0-based 근거 인덱스가 있으며
   `claimType=OPINION`, `groundedness=grounded`인 key point가 하나 이상 있는 기사만 포함한다.
   구조화 analysisSections를 우선하며 기존 keyPoints 형식도 지원한다.
4. 기사 전체 sentiment의 positive/neutral/negative를 낙관/중립/비관으로 대응한다.
   견해 수가 많아도 원문 하나는 표본 하나다. STUB이나 견해 없는 최신 분석을 과거 견해로 대체하지 않는다.

`analyzedArticleCount`는 자체 최신 분석이 LLM/REUSED인 기사 수(중복 제거 전), `sampleCount`는
집계한 독립 원문 수다. 비율의 분모는 중립까지 포함한 sampleCount이고 세 Count의 합과 같다.
Percent는 0~100에서 소수 둘째 자리 HALF_UP이며 합이 반올림으로 0.01 차이 날 수 있다.
표본 0건이면 세 Percent는 null이다. 중립 100%와 구분한다.

화면에는 “견해 포함 기사 논조”, “현재 기준 · 표본 N건”, 직접 분석 기사 수를 표시한다.
이는 분석된 기사 전체의 논조이며 개별 견해 방향이나 모든 매체의 의견 분포를 뜻하지 않는다.
DAILY 보고서에서도 조회 시점의 현재 이슈 분석을 사용한다. 추가 LLM 호출·DB 변경은 없다.

## 검증 (2026-09-04)

- 백엔드 일반 테스트 739개 통과, 조건부 테스트 111개 제외.
- Oracle: 최신 STUB 분석이 과거 OPINION을 되살리지 않는 실제 조회·JSON 변환 테스트 1개 통과.
- 집계/조회/HTTP 계약: 반복 실행, 전재 중복, 독립 기사 ID와 중복군 ID 충돌, 중립 포함 분모,
  FACT/FORECAST/weak/ungrounded 제외, 빈 표본의 null, 대표 요약 fallback,
  미분석 멤버 제외, Oracle 900개 단위 조회 분할 검증.
- FE `pnpm lint`, `pnpm build` 통과.
- 실제 보고서 410의 이슈 1447: 관련 112건, 직접 분석 5건, 적격 표본 0건.
  이슈 58: 관련 309건, 직접 분석 3건, 적격 표본 0건. API에서 null 비율을 반환하며
  브라우저에서 이슈 1447의 “현재 분석에서 집계할 수 있는 견해가 없습니다.” 표시를 확인했다.
- 별도 화면 검증용 데이터로 낙관 66.67% / 중립 0% / 비관 33.33%, 중립 100%, 분석 없음 표시를
  확인했다. 390px 화면에서도 범례와 설명이 줄바꿈되고 가로 넘침이 없었다. 이 값은 실측 분포가 아니다.

```bash
cd BE
./gradlew test
./gradlew test -Dnews.integration.db=true --tests '*FindingRepositoryIntegrationTests.latestFindingForToneDoesNotFallBackToOlderOpinionAfterStubReplacement'
cd ../FE
pnpm lint
pnpm build
```

`docs/plan-master-v2.md`는 저장소 ignore 정책에 따라 로컬 진척만 갱신한다.
