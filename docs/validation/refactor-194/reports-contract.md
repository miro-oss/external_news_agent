# #194 보고서 읽기 화면 계약 확장

기존 GET /api/news/reports, /api/news/reports/latest, /api/news/reports/{reportId}의 메서드·URI·요청 조건·성공/오류 코드·메시지는 유지한다. 보고서 생성 시점의 검증된 본문 및 수집 조건을 저장하고 화면 구성용 필드를 추가한다. Notion 보고서 상세·최신 조회와 목록 제목 설명에 아래 내용을 반영한다.

## 상세·최신 조회 응답 추가 필드

- `structuredContent`: 검증을 마친 보고서 구조. 새 보고서는 Agent 생성과 안전한 대체 생성 모두 저장한다. 기존 보고서는 `null`이며 기존 `markdownBody`를 제목 구획별로 표시한다.
  - `executiveSummary: string[]`: 핵심 요약, Agent 생성 최대 3개.
  - `importantEvents: {title, summaryKo, significance: string|null, sourceFindingIds: number[]}[]`.
  - `watchItems: {topic, reason, sourceFindingIds: number[]}[]`.
  - `sourceNotes: string[]`: 수집 제한·제외 안내. 정상 시 제외 사항 없음 안내는 화면에 별도 박스로 노출하지 않는다.
  - `sourceFindingIds`는 같은 보고서 findings의 ID를 참조한다. UI는 참조가 존재할 때 해당 분석의 기사 상세로 연결한다. 구조화 결과를 화면 관점 전환으로 다시 생성하지 않는다.
- `collectionContexts: {runId: number, topics: CollectionTopicSnapshot[]}[]`: 실행 접수 당시 수집 조건. 같은 실행의 여러 소스에 반복된 동일 주제 snapshot은 중복 제거한다. DAILY는 포함된 실행별 snapshot을 모두 제공하므로 날짜 중간 키워드 변경도 구분한다.
  - `CollectionTopicSnapshot`: `topicId`, `topicName`, `queryText: string|null`, `requiredKeywords: string[]`, `optionalKeywords: string[]`, `excludedKeywords: string[]`, `batchSize`, `intervalMinutes`.
  - snapshot은 실행 접수 시 1회 캡처한다. 대기 중 원본 주제 설정을 편집해도 접수한 수집 조건과 과거 보고서 이름이 바뀌지 않는다.
  - 이전 실행에는 snapshot이 없으므로 `topics: []`이며 현재 설정으로 과거 키워드를 추정하여 채우지 않는다. 이전 보고서는 `collectionContexts: []`일 수 있다.
- `articleStats: {totalCount: number, newCount: number, existingCount: number}`: 실행 관측에서 계산하는 **고유 기사 수**. findings·이슈·주제×소스 조합 수와 다르다.
  - RUN은 해당 실행에서 관측한 articleId 중복 제거.
  - DAILY는 `sourceRunIds` 전체에서 articleId 중복 제거. 실행별 기사 수를 단순 합산하지 않는다.
  - 범위 내 NEW 관측이 한 번 이상 있으면 신규. 나머지는 기존(메타데이터가 변경된 UPDATED와 변경 없는 UNCHANGED 포함).
  - `totalCount = newCount + existingCount`. 화면 필터나 관점 선택으로 변하지 않는다. `includeFindings=false`에서도 반환한다.
- 기존 `summaryStats`는 이전 finding 집계 계약을 유지한다. 화면 상단에는 새 `articleStats`를 사용하고 기존 UPDATED를 '기존 기사'로 라벨만 바꾸지 않는다.

## 제목 및 전달 연결

새 보고서 제목은 접수 시점 주제명이 드러나도록 `HBM 시장 · 2026-09-08 10:00 리포트`, 복수 주제는 `HBM 시장 외 2개 주제 · …`, DAILY는 `HBM 시장 · 2026-09-08 일일 통합 리포트`로 저장한다. snapshot 없는 기존 보고서의 제목은 그대로 유지한다. 목록·상세·저장 markdown 첫 제목에 동일한 제목을 사용한다. 공유 링크 `/#/reports?reportId=17`로 저장된 보고서를 바로 연다.

## 화면 표시 변경

- 범위는 실행별 기본 / 일일 통합 두 가지. 전체 탭·생성 완료 숫자 제거.
- 중요 이벤트는 큰 카드와 이벤트별 내부 카드. 기타 분석 기본 접힘. 기존 markdown도 같은 구획으로 읽을 수 있다.
- 이슈 이름 유지, 작은 설명 `여러 기사를 하나로 묶은 것` 표시.
- `원문 근거 N문장` 총량, 갱신 타일, 민감도 산정 근거, 조사 단계·기사/근거 증분 등 내부 감사 줄은 표시하지 않는다. 기존 API 데이터 및 원문 근거 이동 기능은 유지한다.
- 논조 제목은 유지하되 관련 기사 중 분석 결과가 있는 기사 수와 의견이 확인된 기사 수를 구분한다. 표본 1건은 비율 대신 건수로 표시한다. 상세 집계 기준은 접어서 제공한다. 기존 저장 데이터의 의미·현재 기준 집계는 바꾸지 않는다.
- 관점 선택은 주요 이슈 바로 위로 이동. 저장 요약·이벤트·관찰 항목은 변하지 않고 이슈 순서와 관점별 설명만 변한다.
- 수집 키워드 태그는 추가하지 않는다. 앱 내 확보된 원문에서 snapshot의 실제 일치 단어에만 강조한다. 문장 ID·문장 내용·기존 근거 이동 및 문장 강조는 보존한다. 외부 언론사 웹사이트는 변경하지 않는다.
- 전달 UI는 보고서 최하단. 수신 그룹·수신자 선택과 자동 전달은 알림 계약 문서에 별도 기술한다.

## 저장 및 호환

V40은 `news_collection_run_items.topic_snapshot` JSON CLOB, `news_reports.structured_content` JSON CLOB, `news_reports.collection_contexts` JSON CLOB(default [])를 추가한다. 과거 데이터의 키워드를 현재 설정으로 역채움하지 않는다.

## 검증

- BE: ReportReadingContractTest(고유기사 중복 제거, 접수 후 주제 편집 불변, 구조화/조건 JSON 왕복 및 레거시 null), 기존 보고서 생성·조회·저장·DAILY 회귀.
- FE: `node --experimental-strip-types --test tests/reportReading.test.ts`(원문 보존, 정규식 특수문자, 겹치는 키워드, 당시 실행조건, 제외어, 구 markdown fenced code 보존).
- FE production build 및 lint.
- UI 검증 데이터: [보고서 fixture](../../../FE/tests/fixtures/refactor-report.json); 외부 API 호출 없이 사용한다.

## 반영한 API 명세

- [보고서 상세](https://app.notion.com/p/3b82789f4f588153b3d0ffd55a5178d9)
- [최신 보고서](https://app.notion.com/p/3b82789f4f5881149c0bf199486d5f20)
- [보고서 목록](https://app.notion.com/p/3b82789f4f5881e9802effb3b07f6ea7)

응답 예시와 필드 의미를 수정하고 재조회로 확인했습니다. Swagger의 Schema도 같은 의미를 사용합니다.
