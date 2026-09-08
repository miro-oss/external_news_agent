# #194 수집 설정 계약 변경

## Notion 갱신 대상

- 수집 주제 등록: https://app.notion.com/p/3b82789f4f58814a9244ddf6e732d03d
  - `intervalMinutes` 생략 시 기본값 60 → **1440(24시간)**.
  - 허용값 60/720/1440은 그대로이며 이미 저장한 주기의 일괄 변경은 없음.
  - 요청/응답 기본 예시의 `intervalMinutes`를 1440으로 통일.
- 수집 주제 목록 조회: https://app.notion.com/p/3b82789f4f58813d82cbdf384e225efe
  - 반환 순서는 **ID 내림차순(최근 등록순)**. 정렬 후 페이지를 나눔.
  - 기존 `active` 필터·응답 형태는 유지.
- 수집 주제 활성 토글: https://app.notion.com/p/3b82789f4f58816fa8b5ecdb3183da15
  - API 변경 없음. `PATCH /api/news/topics/{topicId}/activation`, `{active:false/true}`를 화면의 수집 중지/재개에 연결.
  - 진행 중인 수집은 취소하지 않으며 다음 스케줄부터 반영.

## 화면의 입력 매핑

- 기본 `검색 키워드` 한 곳에서 공백/쉼표로 입력. 쉼표를 공백으로 바꾼 질의어는 기존 `queryText`에 전송.
- 기본 모두 포함 필터는 입력한 키워드를 공백/쉼표로 나눈 배열로 구성. SEARCH 결과와 RSS 제목·요약에 동일한 주제 필터 적용.
- `상세 기사 조건`을 펼쳐 모두 포함(required), 하나 이상 포함(optional), 제외(excluded)를 독립 수정 가능. 펼침/접힘 자체는 값에 영향을 주지 않음.
- 고급 조건 값은 쉼표로 구분하므로 공백 포함 구절도 하나의 필터로 사용 가능.
- RSS 연결 시 required/optional을 모두 비워 관련 없는 전체 RSS가 들어오는 입력은 화면에서 막음. optional만 사용하는 조건은 허용.
- 기존 API의 검색어/필터 역할과 저장 데이터 형식은 유지하며 기존 주제는 변경하지 않음.

## 표시·조회 정책

- 등록 주제 기본은 `active=true`, 중지한 주제 화면은 `active=false`.
- 수집 중지/재개 완료 시 주제 목록, 조합 목록, 소스 캐시를 갱신.
- 증가 칩의 burst 여부 색상 차이를 제거. 증가량 데이터와 통계 산식은 유지.
- 제안 패널 기본 닫힘, 전체 폭, 기본은 간단한 변경 목록. 선택한 제안만 상세 비교를 표시.
- 제안 생성은 계속 SCHEDULED 실행에서만 수행하고 수동 실행 정책은 변경하지 않음.

## 검증 기록

- FE `node --experimental-strip-types --test FE/tests/topic-keywords.test.mjs`: 4개 통과. 기본 입력→SEARCH 질의+RSS AND 필터, 상세 OR/NOT·구절 보존, 기본조건 자동추적과 명시적 override, 빈 입력/중복 키워드 검증.
- FE `pnpm --dir FE build`: 통과.
- BE 지정 테스트 8개 클래스 총 119개 통과: TopicCommandServiceImplTest, TopicQueryServiceImplTest, TopicControllerTest, TopicKeywordProposalCommandServiceImplTest, TopicKeywordStrategyOrchestratorTest, ArticleAnalysisPipelineTest, FindingReuseCacheTest, AgentAnalysisOrchestratorTest.
- 브라우저 QA는 root agent가 CUA로 진행. 실제 backend/provider는 이 담당에서 기동하지 않음. mock Vite는 configFile:false 및 빈 envDir로 실행.

## 실행 조건 snapshot과 기사 분석

- AnalysisContext의 optional topicOverride에 접수 시 수집 조건을 전달. 기존 snapshot 없는 실행/호출은 원래 기사 주제로 동작.
- 분석 대상 우선순위, AgentAnalyzeRequest의 topic, finding 재사용 hash가 같은 snapshot을 사용.
- 추가조사 재분석, 대표 보충, 클러스터링 실패 경로, 이슈 멤버의 별도 승격 분석에서도 snapshot을 유지.
- queued HBM 조건 이후 현재 주제를 DRAM으로 바꿔도 HBM 기사 선택/Agent 입력/캐시 hash는 접수한 HBM 기준이며 원본 Topic은 수정하지 않는 회귀 테스트를 추가.

## 교차 검토 후 보완

- `news.collection.scheduler.enabled=false`는 자동 수집만 중지한다. 수동 수집 대기열 dispatcher까지 꺼지지 않도록 전역 스케줄링 토글 `news.scheduling.enabled`를 분리했다. 테스트에서는 둘 다 false로 명시한다.
- 수집 async executor의 메모리 대기열을 0으로 설정해 DB에서 RUNNING으로 가져온 실행이 메모리 큐에서 기다리지 않게 했다. DB PENDING이 유일한 대기열이며, worker 한도가 차면 다시 PENDING으로 복귀한다.
- 관련 큐/config/controller/reaper/creator 지정 테스트 30개 통과. 4개 worker가 함께 시작되고 5번째 요청이 거절되는 실행기 테스트 및 자동수집 off 상태에서도 dispatcher가 동작하는 스케줄링 테스트 포함.
- 자동 전달 옵션을 바꾸면 이전 저장완료 안내를 지운다. 저장/발송 중에는 입력을 잠근다.
- 비활성/삭제된 기존 수신 대상·채널 선택을 숨기거나 자동 삭제하지 않는다. 사용할 수 없는 선택 목록과 개별 해제 버튼을 표시하고, 해결 전 활성 정책 저장/발송을 막는다.
- `delivery-target-availability.test.mjs` 3개 통과: 중지·삭제된 선택 식별, 사용자 해제 전 값 보존, 선택되지 않은 비활성 항목 영향 없음. 수정된 알림 FE 파일 ESLint 및 FE production build 통과.
- 이번 변경 범위 밖 기존 접근성 문제로 기사 모달의 Tab focus trap/종료 후 원래 요소 포커스 복귀 누락을 발견했다. root에 전달했고 추가 수정은 하지 않았다.

## 독립 상호작용 fixture

- 실행 파일: `/private/tmp/refactor-194-settings-ui/interactive-server.mjs`, 포트 5187.
- `/api/*` 전체를 로컬 fixture에서 처리하며 미지원 경로는 `501 QA_BLOCKED`로 실패한다. Vite proxy 없음, `configFile:false`, 빈 envDir 사용. 실제 이메일/텔레그램/수집 요청은 보내지 않는다.
- `/__qa/requests`: 메서드·경로·query·body 로그. `/__qa/state`: 실행·정책·Telegram 상태. `/__qa/reset`: 초기화.
- `/__qa/queue/advance?status=RUNNING|SUCCESS|PARTIAL|FAILED&runId=200`: 실행 상태 전환. runId 생략은 가장 최근 실행. 수집 POST는 PENDING으로 접수하고 idempotencyKey 재사용 시 기존 실행 반환.
- `/__qa/telegram?recipientId=1&status=CONNECTED|WAITING|EXPIRED|DISCONNECTED`: 연결 상태 전환. UI의 Telegram 링크도 이 로컬 주소로만 이동한다.
- `/__qa/email?mode=SMTP|LOCAL_CAPTURE`: 이메일 준비 표시 전환.
- `/__qa/unavailable-policy`: 31번 주제에 비활성/삭제된 저장 선택 생성. 호출 후 화면 새로고침 필요.
- `/__qa/auto-deliveries`: 자동 전달 실패 예시 생성. 보고서에서 실패 대상 재시도 표시를 확인할 수 있다.
