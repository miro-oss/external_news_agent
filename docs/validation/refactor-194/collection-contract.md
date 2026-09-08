# #194 수집 대기 처리 계약

## POST /api/news/runs

요청 필드는 기존 topicIds / idempotencyKey / forceRefresh / plan을 유지합니다.
신규 요청을 DB에 PENDING으로 저장한 뒤 HTTP 201 / COMMON201 / 수집 요청을 접수했습니다.를 반환합니다.
다른 실행과 주제가 겹쳐도 접수를 거절하지 않습니다. 동일 idempotencyKey의 진행 중(PENDING/RUNNING) 요청은 기존 run을 HTTP 200으로 반환합니다.
queuedAt은 접수시각이고 startedAt은 실제 worker가 시작할 때 기록합니다. 생성응답은 null 필드 생략 규칙에 따라 PENDING startedAt을 생략합니다. 목록/상세에서는 null입니다.

## 처리 및 조회

DB dispatch 잠금 안에서 주제 중복과 전역 실행 상한(기본 2, 최대 4)을 함께 검사합니다. 같은 주제를 포함하는 요청은 접수 순서대로 실행하며, 별개 주제 요청은 먼저 시작할 수 있습니다. 선택한 여러 주제는 한 보고서에 포함되는 단일 run으로 접수합니다.
실행 전에 worker가 거절되면 PENDING으로 되돌려 다음 주기에 재시도합니다. 서버 재시작 시 PENDING 요청은 보존합니다. 이미 실행하다 중단된 RUNNING은 기존 로컬 단일 서버 복구 방식으로 실패 처리합니다.
GET /api/news/runs는 queuedAt 내림차순으로 조회합니다. from/to는 기존 실제 시작시각 필터를 유지합니다.
GET /api/news/runs/{runId}도 queuedAt을 제공합니다.

## 화면

여러 활성 주제를 선택하여 접수할 수 있습니다. 같은 대상의 진행 중 요청에서는 중복 방지 키를 재사용합니다.
실행번호/플랜을 성공 안내에서 제거합니다. 대기·수집 중 상태와 완료 후 보고서 링크를 제공합니다.

## 검증

단위 테스트: 접수 영속화, 키 중복 재사용, 예약 중복방지, 주제별 순서, 별개 주제 추월, 전역 상한, worker 거절 후 재대기, 재시작 PENDING 보존.
Oracle 통합 테스트: 동일 주제 서로 다른 요청 둘의 접수, 같은 키 경합, 동시에 실행하는 dispatcher의 주제별 배타성.

## API 명세 및 운영 설정

- [수집 접수](https://app.notion.com/p/3b82789f4f588123ad8ecdb816db9fad)
- [목록](https://app.notion.com/p/3b82789f4f5881eab8c0fc5c9492ac51)
- [상세](https://app.notion.com/p/3b82789f4f5881e8aed0c4d7b45eb986)

`news.collection.scheduler.enabled=false`는 정기 수집만 끕니다. 수동 접수 대기열은 계속 처리합니다. `news.scheduling.enabled=false`는 테스트용 전체 백그라운드 작업 차단이며 이 상태에서는 수동 요청도 대기합니다. 테스트 Gradle 설정은 이 값을 강제합니다. 실제 수집 worker는 메모리 대기열을 사용하지 않고, 최대 4개 worker를 초과하면 DB 대기로 복귀합니다.
