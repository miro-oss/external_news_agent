# #194 화면 상호작용 재현

## 실행

저장소 루트에서 실행한다. `FE/node_modules`가 준비되어 있어야 한다.

```sh
pnpm --dir FE preview:refactor
```

- 수집 설정: http://127.0.0.1:5187/#/settings
- 보고서: http://127.0.0.1:5187/#/reports
- 알림 관리: http://127.0.0.1:5187/#/notifications
- 다른 포트: `pnpm --dir FE preview:refactor --port 5188`
- 종료: 터미널에서 Ctrl+C. 실행 중 데이터는 메모리에만 저장되므로 종료하면 초기화된다.

스크립트는 `FE/scripts/refactor-preview.mjs`, 보고서 데이터는 `FE/tests/fixtures/refactor-report.json`에 있다. 개인 컴퓨터의 절대 경로에 의존하지 않는다. Vite 설정 파일을 읽지 않고 매번 새 빈 envDir를 만들어 실제 `.env` 파일을 사용하지 않는다. 모든 `/api/*` 요청을 메모리 fixture가 처리하고 미지원 경로는 `501 QA_BLOCKED`로 실패한다. backend proxy는 없다.

**실제 기사 수집, AI 호출, 이메일 및 텔레그램 발송은 하지 않는다.** Telegram 연결 버튼이 만드는 링크도 이 로컬 서버에만 연결된다. 이 재현은 화면과 요청 데이터 확인용이며 SMTP·Telegram·수집 서버의 실제 통신 검증을 대체하지 않는다.

## 화면 체크

1. `수집 주제 등록`을 펼친다. `수집 주기`가 기본 `24시간마다`인지 확인한다. 주제명 `QA HBM`, 검색 키워드 `HBM, 반도체`를 입력하고 등록한다. 새 주제가 등록된 수집 주제의 맨 위에 나타나야 한다.
2. http://127.0.0.1:5187/__qa/requests 에서 `POST /api/news/topics`를 확인한다. `queryText`는 `HBM 반도체`, `requiredKeywords`는 `["HBM","반도체"]`, `intervalMinutes`는 `1440`이어야 한다. 상세 기사 조건을 열었다 닫기만 했을 때 값이 바뀌지 않아야 한다.
3. `키워드 제안 검토`를 펼쳐 제안 하나를 선택하고 상세 비교를 본다. 다른 제안을 고르면 그 제안만 상세가 열린다. 화면을 새로고침하면 제안 패널이 다시 접혀 있어야 한다.
4. 등록된 주제의 `수집 중지`를 누른다. 기본 목록에서 즉시 사라지고 `중지한 주제 보기`에서 나타나야 한다. 해당 주제의 `수집 재개` 후 기본 목록으로 돌아오면 다시 보여야 한다. 중지/재개 순서는 등록 순서를 바꾸지 않는다.
5. 수집할 주제를 2개 이상 체크하고 `선택 주제 수집`을 누른다. 빨간 중복 실행 오류나 실행 번호 대신 접수 안내 및 대기 상태를 보여야 한다. 요청 로그의 `topicIds`가 선택한 주제와 같아야 한다.
6. 별도 탭에서 http://127.0.0.1:5187/__qa/queue/advance?status=RUNNING 을 연다. 수집 설정의 상태가 약 2초 안에 `수집하고 있습니다`로 바뀐다. 이어 http://127.0.0.1:5187/__qa/queue/advance?status=SUCCESS 를 열면 완료 안내와 `보고서 보기` 링크가 나타나야 한다. 큐 상태를 자동으로 진행하지 않으므로 각 단계를 안정적으로 관찰할 수 있다.
7. 알림 관리에서 `김수신`의 `텔레그램 연결`을 누른다. WAITING 상태 안내, Telegram 열기, 링크 복사가 나타나야 한다. `Telegram 열기`로 로컬 연결 완료 주소를 연 뒤 원래 화면에 돌아오면 약 3초 안에 연결됨으로 바뀐다. 실제 Telegram에 접속하지 않는다.
8. 수집 설정에서 `반도체 수출 규제와 공급망`의 `자동 전달`을 연다. 실행별/일일 통합, 그룹/개인/전달 방식을 선택하고 저장한다. 성공 안내가 나타난 후 옵션을 바꾸면 이전 성공 안내가 사라져야 한다. 새로고침해 다시 열면 마지막으로 저장한 값이 유지된다.
9. http://127.0.0.1:5187/__qa/unavailable-policy 를 연 뒤 화면을 새로고침하고 같은 주제의 자동 전달을 다시 연다. 중지·삭제된 기존 선택이 경고 목록에 보여야 한다. 선택을 자동 제거하지 않으며 하나씩 해제할 수 있다. 활성 정책은 잘못된 선택을 해결하기 전 저장할 수 없다. 자동 전달을 끄는 저장은 기존 선택을 보존한다.
10. 보고서는 `실행별`이 기본이고 `일일 통합`이 옆에 있다. 두 번째 실행별 보고서는 저장된 Markdown fallback, 첫 번째는 구조화 카드 표시를 검증한다. `기타 분석`은 기본 접힘, 공유는 본문 아래, 원문 모달에서 입력 키워드는 강조되어야 한다.
11. 화면 폭 390px과 데스크톱에서 확인한다. 표·보고서 목록 레일은 자기 영역 안에서만 스크롤되고 페이지 전체 가로 넘침이 없어야 한다. 기존 카드 패딩·반경·색상·버튼 누름 동작이 유지되어야 한다.

## 상태 제어

아래 주소는 로컬 검증 데이터만 바꾼다. 화면의 React Query 캐시가 남아 있으면 새로고침한다. 포트를 바꿨다면 주소의 5187도 바꾼다.

| 경로 | 기능 |
| --- | --- |
| `/__qa/reset` | 모든 데이터와 요청 로그 초기화 |
| `/__qa/requests` | 메서드·경로·query·body 요청 로그 |
| `/__qa/state` | 실행·정책·Telegram·메일 준비 상태 |
| `/__qa/queue/advance?status=RUNNING` | 마지막 접수 실행 상태 변경 |
| `/__qa/queue/advance?runId=200&status=PARTIAL` | 특정 실행 상태 변경; PENDING/RUNNING/SUCCESS/PARTIAL/FAILED 사용 |
| `/__qa/telegram?recipientId=1&status=EXPIRED` | Telegram 연결 만료; CONNECTED/WAITING/EXPIRED/DISCONNECTED 사용 |
| `/__qa/email?mode=SMTP` | 이메일 준비됨 표시; LOCAL_CAPTURE로 로컬 미발송 표시 복귀 |
| `/__qa/unavailable-policy` | 31번 주제에 중지/삭제된 저장 선택 생성 |
| `/__qa/auto-deliveries` | 보고서 하단 자동 전달 실패 예시 및 재시도 버튼 표시 |

## 실행 검증 기록

- portable 스크립트를 `pnpm preview:refactor --port 5188`로 실행하고 종료까지 확인했다.
- fixture HTTP smoke 확인 통과: 최신 등록순·1440 기본 표시 데이터, 주제 생성·중지·재개, 수집 PENDING 접수·동일 키 재사용·RUNNING·완료와 보고서 연결, Telegram의 로컬 링크·WAITING·CONNECTED, 사용할 수 없는 정책, 미지원 API 501 차단.
- 제품 FE build 및 수정 알림 컴포넌트 ESLint 통과. 관련 native Node 테스트는 입력 키워드 4개와 전달 대상 가용성 3개가 통과했다.
- 실제 UI 조작과 화면 캡처는 root agent가 CUA로 수행한다.
