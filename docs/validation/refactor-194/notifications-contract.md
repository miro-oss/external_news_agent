# #194 보고서 전달 및 텔레그램 연결 계약

기존 Notion `API 명세서`의 보고서 발송·미리보기·발송 이력·채널 목록을 읽고 확장한다. 기존 URI, 공통 성공/실패 봉투, HTTP 응답 코드를 유지한다. 아래 새 API는 모두 `/api/notifications` 아래에 있다. JSON 요청은 `Content-Type: application/json`. 공통 200 성공은 `{isSuccess:true,code:"COMMON200",message:"성공입니다.",result:...}`다. 입력 오류는 COMMON400/HTTP400, 없는 주제는 COMMON404/HTTP404, 없는 수신자는 RECIPIENT404/HTTP404, 없는/비활성 그룹은 GROUP404/HTTP404, 없는/비활성 채널은 CHANNEL404/HTTP404다. 비밀값은 요청/응답/로그에 포함하지 않는다.

## 기존 보고서 발송 확장

`POST /reports/{reportId}/send`

```json
{"groupIds":[1],"recipientIds":[2],"channelIds":[1,2],"idempotencyKey":"report-17-share-001"}
```

- groupIds 또는 recipientIds 중 하나 이상. 그룹과 개인의 합집합이며 수신자+채널 기준으로 중복 제거.
- recipientIds는 선택한 활성 개별 수신자. 비활성 수신자는 발송 대상에서 제외한다.
- 그룹 대상 계산, channelIds 생략 시 활성 전체, idempotencyKey 및 기존 결과/오류 규칙 유지.
- 미리보기와 실제 발송은 같은 renderer를 사용한다.
- 보고서 생성 시 저장한 executiveSummary 최대 3개를 각 280자 이내로 전달한다. 저장된 구조가 없는 이전 보고서는 저장된 Markdown의 요약 섹션을 우선 사용하고 없으면 개별 분석 요약 최대 3개로 대체한다.
- 전체 기사 목록을 순회하여 메시지를 증식하지 않는다. Telegram은 안전 길이 내 메시지 1개, 이메일도 같은 보고서 요약을 구조화하여 제공한다. 대표 원문 최대 3개 링크 제공. 기사 전문은 전달하지 않는다.
- `news.notifications.public-base-url`이 HTTP(S)로 설정된 경우 `/#/reports?reportId={id}` 전체 보고서 링크를 추가한다. 설정이 없으면 URL을 추측하지 않고 원문 링크만 제공한다.
- HTML 엔티티는 텍스트로 정규화한 뒤 UTF-8 HTML escape를 적용한다. 디코딩된 텍스트를 HTML로 재파싱하지 않는다.

## 주제별 자동 전달

`GET /topics/{topicId}/delivery-policy` / `PUT /topics/{topicId}/delivery-policy`

PUT 요청과 두 API의 result:

```json
{"enabled":true,"run":true,"daily":false,"groupIds":[1],"recipientIds":[2],"channelIds":[1,2]}
```

- 저장 전 기본값 enabled=false, run=true, daily=false, 목록 빈 배열.
- PUT은 전체 교체. enabled=true이면 보고서 종류, 대상(그룹/개인), 채널을 각각 하나 이상 지정한다.
- 각 목록은 양의 ID 최대 100개, 중복 제거. enabled=true일 때 활성 그룹/수신자/채널을 검증한다. enabled=false이면 ID 형식·개수 제한만 유지하며 중지·삭제된 기존 선택도 그대로 저장한다. 기존 대상이 유효하지 않아도 자동 전달을 끌 수 있어야 한다.
- RUN은 해당 주제가 포함된 수집 실행 완료 보고서. DAILY는 해당 주제가 포함된 날짜 전체 통합 보고서. 여러 주제/그룹 정책에 같은 사람이 있어도 같은 보고서+수신자+채널은 1회만 전송한다.
- 둘 다 선택하면 별개의 실행 보고서와 일일 통합 보고서를 각각 전달한다. 화면에 이를 설명한다.
- 보고서 저장 완료와 같은 DB 트랜잭션에 대상별 outbox와 배치를 저장한다. 브라우저를 열 필요 없이 서버 worker가 커밋된 행을 처리한다. 전송 직전에 한 대상씩 선점하고 scheduler 실행 한 번에 최대 30명을 순차 처리한다. 아직 전송하지 않은 대상은 PENDING으로 유지한다.
- 비활성화된 채널·수신자, 해제/변경된 수신 주소는 전송 직전 재검사해 SKIPPED 처리한다.
- 확정 실패만 최대 5회 지수 지연으로 재시도한다. 이미 성공한 수신자에게 재전송하지 않는다.
- 전송 이후 응답/저장 여부가 불확실하거나 처리 중 5분 이상 중단된 행은 UNKNOWN으로 남긴다. 만료 전송의 발송 이력에는 FAILED와 결과 미확인 설명을 기록하고 배치 완료 시각도 남긴다. 외부 SMTP/Telegram이 idempotency를 보장하지 않으므로 불확실한 전송을 자동 재전송하지 않는다.

`GET /reports/{reportId}/auto-deliveries`

```json
[{"id":1,"recipientName":"수신자","channelType":"EMAIL","status":"SENT","attempts":1,"message":null}]
```

status: PENDING / PROCESSING / SENT / FAILED / SKIPPED / UNKNOWN. 개인정보 주소, 토큰은 이 응답에 없다.

`POST /reports/{reportId}/auto-deliveries/retry` 요청 `{}`, result `{"queuedCount":1}`

FAILED만 다시 대기열에 넣는다. SENT·UNKNOWN·SKIPPED 제외. 기존 발송 이력에도 각 전송 시도와 공개 오류를 기록한다. UNKNOWN은 기존 이력 enum을 깨지 않도록 FAILED + 결과 미확인 메시지로 기록하고 이 API에서 구분한다.

## 텔레그램 개인 연결

`POST /recipients/{recipientId}/telegram/link` 요청 `{}`

```json
{"url":"https://t.me/example_bot?start=<일회성난수>","expiresAt":"2026-09-08T16:10:00+09:00"}
```

- 활성 수신자에게 귀속되는 32바이트 암호학적 난수, base64url 43자. DB에는 SHA-256 해시만 저장.
- 생성 후 10분 유효, 1회 사용. 새 링크를 만들거나 연결 해제하면 이전 미사용 링크는 무효.
- 연결 링크는 수신자를 선택한 관리자가 해당 사람에게 복사해서 전달하거나 직접 Telegram에서 열 수 있다. 시스템이 이 링크나 메시지를 실제 수신자에게 자동 발송하지 않는다.
- Telegram private chat에서 from.id=chat.id, bot=false인 `/start <token>`만 처리. 유효 토큰의 recipient_id로만 귀속한다. 임의 Chat ID 수동 입력 UI 제거.
- 이미 다른 수신자에 연결된 Chat은 덮어쓰지 않는다. 링크를 소지한 사람을 연결하는 절차이므로 다른 사람에게 링크를 전달하면 그 사람의 Chat이 연결될 수 있음을 대상 이름과 함께 안내한다.
- `/start` 없이 메시지를 받거나 Telegram 외부에서 chat id를 추측하여 연결하는 경로는 없다.
- 기존 봇 토큰을 사용해 getMe로 사용자명을 얻는다. getWebhookInfo에서 기존 webhook이 있으면 연결 링크 생성을 거절하며 webhook을 삭제/변경하지 않는다.
- 현재 구성은 서버 getUpdates 수신 방식. 만료되지 않은 대기 링크가 있을 때만 polling하며 DB cursor/lease로 수신 위치·동시 polling을 관리한다. 서버가 재시작되어도 연결 상태/수신 위치는 남는다.
- 신규 일반 UI API로 Telegram Update를 주입할 수 없고, 수신은 설정된 Bot API HTTPS endpoint만 이용한다.

`GET /recipients/{recipientId}/telegram`

result `{"status":"CONNECTED","expiresAt":null}` 또는 WAITING / EXPIRED / DISCONNECTED. 연결된 Chat ID 및 URL/토큰을 상태 응답에 재노출하지 않는다. 프론트는 WAITING일 때 3초 간격 조회해 연결 완료를 표시한다.

`DELETE /recipients/{recipientId}/telegram`

result `{"status":"DISCONNECTED","expiresAt":null}`. 해당 수신자의 Telegram destination 제거, 미사용 링크 취소. Email destination은 유지.

봇 미설정: COMMON400 `텔레그램 연결 설정이 준비되지 않았습니다.`
기존 webhook: COMMON400 `이 봇은 다른 연결 서비스를 사용 중입니다. 관리자에게 연결 설정 확인을 요청해 주세요.`
연결 서버 실패: COMMON400 `텔레그램 연결 서버에 접속하지 못했습니다. 잠시 후 다시 시도해 주세요.`
비활성 수신자: COMMON400 `활성 수신자만 연결할 수 있습니다.`

Telegram 공식 계약 근거: https://core.telegram.org/bots/features#deep-linking 및 https://core.telegram.org/bots/api#getupdates

## 메일 경로 진단

`GET /email-readiness` result:

```json
{"mode":"LOCAL_CAPTURE","configured":true,"message":"현재 메일은 테스트 보관함(Mailpit)에 저장됩니다. 실제 이메일 수신함으로 전달되지 않습니다."}
```

- mode: UNCONFIGURED / LOCAL_CAPTURE / SMTP. 공개 channel config의 host/port/from 존재와 Mailpit 기본 주소만 판별한다.
- 실제 SMTP 접속이나 테스트 발송을 수행하지 않는다. configured=true는 설정 존재이며 전송 성공 검증이 아니다.
- 초기 DB seed localhost:1025가 Mailpit 경로이다. 사용자의 실제 환경값은 읽지 않았으므로 실제 이메일 미수신 원인은 확정하지 않는다.
- SMTP 인증 실패, 접속 실패, 주소 거절, 결과 미확인을 공개 메시지로 구분하며 토큰·비밀번호·서버 예외 URI를 출력하지 않는다.
- 실제 수신 검증은 사용자의 별도 실제발송 승인 후 발송 이력(Message-ID)→SMTP 접수→수신함/스팸함 순서로 한다. 이번 개발 검증은 가짜 transport와 격리 DB만 사용하며 실제 수신자에게 발송하지 않는다.

## 검증 범위

- 렌더러: 보고서 구조화 요약 우선, 기사 수 증가해도 메시지 1개, 원문 전문 미포함, 엔티티/스마트따옴표/문자로 된 태그 회귀.
- worker: 중복 성공 제외, 확정 실패와 불확실 결과 분리, 비활성 destination 제외, 전송 결과 저장 실패 시 자동 재전송 금지.
- 연결: private chat 검증, 만료·재사용·재발급·해제, 잘못된 수신자 귀속/기존 주소 탈취 방지, DB cursor 유지.
- API/UI: 그룹+개별수신자 합집합, 설정 유지, Telegram 연결 상태, Mailpit 안내, 본문 하단 공유.

## Notion 반영 결과

기존 보고서 발송·미리보기·발송 이력·채널 목록·수신자 등록·수신 주소 설정 6개 페이지를 갱신했고, 아래 8개 엔드포인트를 API 데이터베이스에 등록했다. 기존 명세 템플릿의 Description/EndPoint/Request Header/Path Variable/Query String/Request Body/Response Body 구조를 유지했고, 변경 뒤 14개 페이지를 모두 다시 조회하여 확인했다.

- [주제 자동 전달 설정 조회](https://app.notion.com/p/3d52789f4f588113bfa6ce48edfcfec9)
- [주제 자동 전달 설정 저장](https://app.notion.com/p/3d52789f4f5881b18835c4c2ca50c996)
- [텔레그램 연결 상태 조회](https://app.notion.com/p/3d52789f4f5881deb09ce9988d6e121a)
- [텔레그램 연결 링크 생성](https://app.notion.com/p/3d52789f4f5881ddacbee1298281d99d)
- [텔레그램 연결 해제](https://app.notion.com/p/3d52789f4f58818ab0e0c114c5b306d2)
- [보고서 자동 전달 상태 조회](https://app.notion.com/p/3d52789f4f58818f895ce23419b05488)
- [실패한 보고서 자동 전달 재시도](https://app.notion.com/p/3d52789f4f5881d8b6eef573abff809d)
- [메일 전달 경로 안내 조회](https://app.notion.com/p/3d52789f4f588101b9e8d32dd175f0e5)

### 실행 중 서비스 읽기 진단

2026-09-08 기존 127.0.0.1:8080 서비스에 채널 목록·EMAIL 발송 이력 GET만 수행했다. EMAIL은 활성 및 설정 존재 상태이고 SMTP 포트465를 사용하며, Mailpit 기본 경로가 아니었다. EMAIL 발송 이력 조회는 성공/실패/제외 모두 0건이었다. 따라서 조회된 데이터로 SMTP 실패나 수신함 문제를 확정할 수 없다. 기존 화면은 한 채널만 선택했으므로 Telegram만 선택되어 이메일 발송 요청이 없었을 가능성은 남는다. 새 화면에서는 Email과 Telegram을 함께 선택할 수 있다. SMTP 접속/실제 메일 발송은 수행하지 않았다. 원시 주소·host·토큰·환경설정값을 문서에 저장하지 않았다.


보고서 자동 전달 상태 조회 및 재시도는 존재하지 않거나 PENDING인 보고서에 HTTP404/REPORT404/`보고서를 찾을 수 없습니다.`를 반환한다.

새 NotificationConnectionController의 8개 엔드포인트에 정상 응답 및 주요 입력/404 오류 코드·메시지 JSON 예시를 Swagger/OpenAPI에 함께 반영했다. SMTP 연결·읽기 외에 쓰기에도 readTimeout과 같은 제한을 적용하여 전송 정지를 무한히 기다리지 않는다.
