# 자기 검증 KEEP 실패 회귀 검증

- 작업: [#164](https://github.com/miro-oss/external_news_agent/issues/164)
- 기준: 2026-09-04, 수정 전 `44298d2`, 로컬 실행 `4483`
- 내부 계약 정본: [Agent README](README.md#v1analyze-자기-검증-계약),
  [Spring / Python 경계](../AGENT_BOUNDARIES.md#3-현재-http-어댑터와-계약)

## 확인한 원인

사용자가 제공한 터미널 로그에서 자기 검증 실패 4건 모두
`KEEP 결과는 기존 주장 값을 바꿀 수 없습니다.`로 거절됐다. 기존 구현은 KEEP 응답에
모델이 복사한 본문·근거·신뢰도·판정 이유가 원본과 정확히 같아야 성공했다.
전체 provider 응답은 보존되지 않았으므로 실제 4건에서 어느 필드가 달랐는지는 확인하지 못했다.

재현은 실제 응답 재생이 아닌 합성 fixture로 수행했다. 수정 전 모듈을 `git show`로 별도
메모리 모듈에 읽어 실행했을 때, 판정 이유만 바꾼 KEEP이 동일한 오류와 HTTP 502에 해당하는
`SCHEMA_VIOLATION`을 발생시켰다. 잘못된 claimId 응답도 실패했으며, 두 경우 모두
provider가 반환한 사용량이 있어도 오류 `details`가 `null`이었다.

별도로 Java의 record 동등성 비교는 신뢰도 `BigDecimal("0.60")`과 Python JSON 왕복 후
`0.6`을 다른 값으로 판단했다. 두 번째 주장의 `0 → 0.0`도 변경 건수에 포함되어
정상 유지 응답 또는 실제 주장 한 건만 수정한 응답까지 거절할 수 있었다.
이 문제는 백엔드 회귀 테스트로 재현했으며, 제공된 4건의 로그 원인과 구별한다.

## 수정 결과

- KEEP은 출력 스키마와 대상 claimId를 검증한 뒤 서버가 원본 bullet 전체를 반환한다.
  모델이 다시 작성한 값은 사용하지 않으며 요약과 수정하지 않은 다른 주장도 보존한다.
- REVISE의 기존 근거 부분집합 제한, 결정론적 사실·전망 검증, REJECT의 빈 근거와
  `confidence=0` 조건은 유지한다. 실제로 바뀐 주장은 최대 한 건이어야 한다.
- Spring은 이 자기 검증 비교에서만 신뢰도의 숫자 값을 비교한다. 나머지 필드는 모두 비교하며,
  전역 record 동등성이나 API DTO를 변경하지 않는다.
- 프롬프트를 `self-critique.ko.v2`로 구분하고 KEEP 처리, REJECT 신뢰도, 출력 필드를 명시했다.
  구조화 생성은 기존처럼 한 번이고 schema repair는 추가하지 않았다.
- provider 출력 계약 실패의 관측 사용량과 truncation 정보를 기존 오류 details 형식으로 전달한다.
  과거 실패에서 누락된 사용량이나 실제 비용을 소급 복원하지 않는다.

## 검증

Python:

```sh
cd agent
.venv/bin/python -m pytest tests/test_self_critique_service.py tests/test_api.py tests/test_schema.py
.venv/bin/ruff check app/llm/self_critique_service.py tests/test_self_critique_service.py
```

59개 테스트가 통과했다. 그중 자기 검증 20개는 KEEP 원본 보존, 잘못된 ID·스키마 거절,
REVISE/REJECT 제한, 1회 호출, HTTP 200 유지 응답과 HTTP 502 실패 사용량 전달을 확인한다.
Provider는 테스트 대역이며 외부 모델을 호출하지 않았다.

Java:

```sh
cd BE
./gradlew test --tests '*AgentAnalysisOrchestratorTest' --tests '*AgentClientTest' --no-daemon
```

숫자 자릿수 관련 새 회귀 7개 중 정상 경로 4개가 수정 전에 실패하고 수정 후 통과했다.
실제 값 변경과 수정 건수 불일치 거절도 확인한다. 오류 수신·자기 검증 실패 감사·quota
정산 호출 테스트는 반환된 사용량을 전달하는지 확인한다. Orchestrator 36개와 Client 11개,
총 47개가 통과했다. 기존 정책상 이 두 실패 코드의 quota 예약은 해제되며, 관측 사용량은
별도의 감사 기록에 전달된다. 이번 변경에서 quota 정책을 바꾸지는 않았다.

## 로컬 적용과 다음 관찰

Uvicorn과 IntelliJ 백엔드는 자동 reload 설정이 아니므로 코드 변경 후 재시작해야 적용된다.
위 테스트는 코드·계약 회귀 검증이며 실제 Gemini 호출과 구별한다. 재시작 후 실제 실행
결과는 아래에 별도로 기록한다. 기존 독립 검증 자료와 과거 실행 결과는 변경하지 않았다.

제공된 로그의 `ConnectError`/503은 이 KEEP 오류와 별개다. 이후 분석·근거 검증·보고서 호출이
200으로 복구된 점은 확인되지만, 연결 오류가 재발하지 않는지는 다음 실행에서 관찰해야 한다.
기존 부분 성공 실행을 정상 실행 10회 비용 표본으로 바꾸지 않는다.

## 재시작 후 실제 실행 결과

2026-09-04 14:36:20–14:56:01 KST에 커밋 `949c3db`를 적용한 로컬 서버로 수동 실행
`4484`를 한 번 수행했다. 두 검증 주제의 자동 수집 일정은 9월 5일 11:26:06으로 유지됐다.

| 구분 | 결과 |
|---|---|
| 자기 검증 | 실제 Gemini `self-critique.ko.v2` 5건 성공, 실패 0건 |
| 이전 실패와 비교 | 이슈 4355는 이번에 성공. 이전 실패의 나머지 3개 이슈는 이번 자기 검증 대상에 포함되지 않음 |
| 일반 분석 | 성공 33건, `SCHEMA_VIOLATION` 4건. 추가 조사 재분석 포함 |
| 근거 검증 | provider 27건, 규칙 6건 모두 성공 |
| 추가 조사 | 성공 2건, 실패 2건 |
| 보고서 | 보고서 482, `GENERATED` |
| 연결 오류 | 이번 실행의 `PROVIDER_UNAVAILABLE` 기록 0건 |
| 실행 전체 | `PARTIAL`. 수집 1,766건: 신규 106, 갱신 10, 건너뜀 1,650 |

자기 검증 수정은 실제 서버 간 호출과 감사 저장까지 확인했다. provider 원문 응답의
KEEP/REVISE/REJECT action은 감사에 저장되지 않아 실제 KEEP 횟수는 확정하지 않는다.
KEEP의 원본 보존 자체는 앞의 합성 회귀 테스트로 검증한다.

전체 실행의 잔여 실패는 별도 경로다. 조사 요청에 엔티티 70개가 전달되어 상한 50개에 걸렸고,
다른 조사 행동은 기존 수집 관측을 다시 저장해 `UQ_RUN_ARTICLE` 제약에 걸렸다.
일반 분석 실패 4건은 공통 출력 계약 오류만 DB에 남았다. 이 경로의
`include_failure_details=False` 설정 때문에 실패 사용량도 누락되어 추가 진단이 필요하다.
일부 피드와 robots 제한도 남아 있으므로 이 실행을 정상 실행 10회 비용 표본에 포함하지 않는다.
실비 0원이나 전체 파이프라인 안정화 완료를 뜻하지 않으며, 기존 독립 검증 자료도 변경하지 않았다.
