# P3-1 일일 통합 보고서 — #156

검증일: 2026-09-04. 관련 이슈: [#156](https://github.com/miro-oss/external_news_agent/issues/156).

## 실제 입력 토큰 측정

로컬 Oracle의 완료된 수집 실행과 실제 Gemini 응답 사용량을 비교했다. 가장 최근의 **모든 실행이
종료된 날짜**는 2026-09-01이었다. 9월 2·3일은 진행 중 상태의 실행이 있어 집계를 보류했다.

| 항목 | 측정값 |
|---|---:|
| 집계일 (Asia/Seoul) | 2026-09-01 |
| DAILY 보고서 ID | 410 |
| 포함한 수집 실행 | 17회 |
| 비교 가능한 GENERATED 실행별 보고서 | 6건 |
| 실행별 보고서 입력 토큰 합계 | 79,209 |
| 일일 보고서 입력 토큰 | 6,779 |
| 입력 절감 | **91.44%** |
| 선택한 이슈 | 10개 |
| 모델 | gemini-3.6-flash |
| 일일 생성 상태 | GENERATED |

17회 중 provider 사용량이 기록된 GENERATED 보고서 6건만 비교 분모에 포함했다. MOCK·FALLBACK은
포함하지 않았다. 이 결과는 한 날짜의 실측이며 모든 입력 분포의 절감률을 보장하지 않는다.
FREE 호출의 credits·costUsd 기록은 모두 0이므로 금전 비용 절감률로 환산하지 않는다.

같은 날짜를 다시 생성해도 ID 410을 반환했다. `agent_runs`에는 `REPORT` task /
`REPORT` target / target ID 410 / `collection_run_id=NULL`인 감사 행이 정확히 1개였다.

## 생성·조회 계약

- V39: `report_scope`, `report_date`, `source_run_ids`. 기존 RUN의 `run_id` 유니크 제약 유지,
  DAILY는 `run_id=NULL`과 날짜별 함수 기반 유니크 인덱스 및 범위 CHECK로 보호한다.
- 한국 시간 `[집계일 00:00, 다음 날 00:00)`에 **시작한** 실행을 집계한다. 진행 중 실행이 하나라도
  있으면 완료될 때까지 기다린다. 실패·부분 성공 실행도 저장된 분석이 있다면 후보에 포함한다.
- 최신 finding을 이슈별 1개로 선택한 뒤 근거 유효성을 확인한다. 최신 분석이 근거를 잃었으면
  과거의 주장을 되살리지 않는다. 현재 중요도 내림차순, 동률 이슈 ID 오름차순으로 상위 N개를 선택한다.
- N 기본값은 10, 허용 범위는 1~50이다. DAILY 입력은 finding별 검증된 주장 최대 3개와 짧은 요약을
  사용하며 근거 인덱스·weak·FORECAST·OPINION·발화 주체를 유지한다. 본문 원문이나 실행별 보고서
  전체를 다시 넣지 않는다. 기존 report.ko.v1.4의 최종 근거 검증·쿼터·fallback을 재사용한다.
- 5분마다 종료된 날짜를 확인하고 최근 7일의 누락을 보충한다. 실행이 없는 날은 생성하지 않고,
  실행은 있지만 적격 이슈가 없는 날은 LLM 호출 없이 빈 근거를 설명하는 대체 보고서를 만든다.
- 짧은 DB 잠금으로 생성 소유자를 예약하고 HTTP 호출 전에 커밋한다. 30분 이상 남은 PENDING은
  저장한 finding ID로 복구하며 불확실한 LLM 호출을 반복하지 않는다. 완료된 DAILY는 덮어쓰지 않는다.
- 목록·최신 API의 선택 query `reportScope=RUN|DAILY`, 모든 보고서 응답의
  `reportScope`·`reportDate`·`sourceRunIds`, finding별 `runId`를 Notion과 Swagger에 반영했다.
  DAILY 조회·통계·발송은 저장된 finding을 사용하며 원문 근거 조회는 각 finding의 실행 ID를 사용한다.
- 일일 화면의 제목·요약은 저장된 finding을 사용한다. 이후 이슈 병합으로 선택된 항목을 다시 숨기지
  않는다. 관련 기사 묶음은 현재 이슈 데이터임을 표시한다.

## 검증과 재현

단위/계약 테스트: 최신 분석·중복 이슈·중요도 동률·상위 N개·다른 주제·병합 이슈 제외,
자정 경계, 중복 예약, 장애 격리, 중단 복구, DAILY/RUN 혼합 입력 거절, 보고서 필터,
근거별 실행 ID·저장된 finding 집계·메일/텔레그램 렌더링을 확인했다.

Oracle 통합 테스트: V39 적용과 Hibernate 검증, 자정을 넘긴 RUNNING 실행의 집계 보류,
동일 날짜 경쟁 요청의 소유자 1명, DAILY의 null run 조회, 기존 RUN 보고서 생성 회귀를 확인했다.

최종 검증은 독립 Oracle 스키마에서 전체 백엔드 830개 통과·조건부 3개 제외, Agent 233개 통과,
Ruff·골든 replay 회귀 0건, FE 빌드·린트 통과였다. 실제 수집 데이터가 있는 로컬 스키마에서는 기존
초기 수집원·알림 큐 상태를 가정한 테스트 5개가 실패했지만 독립 스키마에서 모두 통과했다.
브라우저에서 일일 필터, 저장된 분석 카드, 원문 근거 모달의 실행 ID 연결도 확인했다.

```bash
cd BE
./gradlew test
./gradlew test -Dnews.integration.db=true --tests '*ReportOracleIntegrationTests'
# 전체 DB 테스트는 독립 테스트 스키마를 datasource로 지정한 뒤 실행
./gradlew test -Dnews.integration.db=true
```

실측은 **로컬 Agent와 provider 설정이 준비된 경우만** 명시적으로 실행한다. 실제 일일 보고서를
저장하며 신규 날짜는 LLM 호출이 발생할 수 있다. 기존 날짜는 저장 결과를 재사용한다.

```bash
./gradlew test -Dnews.reports.daily.measure=true --tests '*DailyReportMeasurementIntegrationTests'
# 특정 종료일을 지정할 경우 -Dnews.reports.daily.measure-date=2026-09-01 추가
```

결과 파일: `BE/build/reports/daily-report-measurement.json`. 비밀값이나 요청 본문은 기록하지 않는다.
일반 테스트에서는 실측 테스트가 실행되지 않으며 수집 스케줄러도 꺼진다.
