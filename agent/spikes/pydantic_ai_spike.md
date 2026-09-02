# P2-4 PydanticAI 도입 가능성 spike

측정일: 2026-09-02<br>
이슈: #136<br>
대상: `pydantic-ai-slim[openai] == 2.36.0`

## 결론

**P2-5 조사 루프에 PydanticAI를 조건부 도입한다.** 세 필수 계약이 모두 충족된다.
다만 기본 설정만으로는 현재 시스템과 호환되지 않으므로 아래 어댑터를 함께 적용해야 한다.

1. `OpenAIChatModel`을 사용하고 기본 Responses API 경로를 사용하지 않는다.
2. `MindlogicCreditsOpenAIChatModel`로 비표준 `usage.credits`를 보존한다.
3. `MindlogicStrictJsonSchemaTransformer`로 Mindlogic이 받지 않는 7개 키를 제거한다.
4. `NativeOutput(..., strict=True)`와 `retries=0`을 고정한다.
5. 기존 `AnalyzeProvider`, provider guard/retry/breaker, Spring 쿼터 예약·정산을 정본으로 유지한다.

이번 spike의 PydanticAI 의존성은 dev dependency에만 고정했다. production 전환은 P2-5에서
위 어댑터를 기존 provider 경계 안에 연결할 때 수행한다.

## 측정 환경

- Python: 프로젝트 계약 `>=3.12` (로컬 실행 3.13)
- PydanticAI: `2.36.0` 고정
- OpenAI SDK: lockfile 기준 `3.7.0`
- 검증 코드: `spikes/pydantic_ai_spike.py`, `tests/test_pydantic_ai_spike.py`
- 공식 근거:
  - [OpenAI-compatible model과 `base_url` 구성](https://pydantic.dev/docs/ai/models/openai/#openai-compatible-models)
  - [Chat Completions API 모델 선택](https://pydantic.dev/docs/ai/models/openai/#chat-completions-api)
  - [PydanticAI slim 2.36.0 배포 메타데이터](https://pypi.org/project/pydantic-ai-slim/2.36.0/)

실제 API 키와 실제 LLM 호출은 사용하지 않았다. 끝 슬래시 라우팅만 무인증 요청으로 측정했으며
두 요청 모두 인증 단계에서 종료됐다.

## 판정표

| 필수 확인 항목 | 측정 | 판정 |
|---|---|---|
| `/chat/completions/` 끝 슬래시 | PydanticAI/OpenAI SDK의 요청은 `/chat/completions`로 끝난다. Mindlogic 운영 URL의 무인증 POST는 슬래시 없음/있음 모두 `401`, redirect 없음 | **PASS** — URL rewrite 불필요 |
| `usage.credits` 추출 | 기본 `RunUsage.details`는 문자열 소수 크레딧을 보존하지 않는다. 응답 hook에서 원문을 문자열로 보존하고 기존 `_usage_decimal`로 복원했을 때 `1.750000000000000000123`가 정확히 일치 | **PASS(어댑터 필수)** |
| strict 스키마 | 전용 transformer가 OpenAI strict 변환 후 기존 7개 비지원 키를 모든 깊이에서 제거한다. `strict: true`, 모든 속성 required, `additionalProperties: false`도 유지 | **PASS(전용 profile 필수)** |

추가로 `retries=0`에서 잘못된 JSON 응답이 들어오면 HTTP 요청이 정확히 1회만 발생함을 확인했다.
따라서 기존 rate-limit/provider retry와 PydanticAI retry가 중첩되지 않는다.

## 세부 결과

### 끝 슬래시

자동 테스트가 캡처한 URL은 `https://gateway.test/v1/gateway/chat/completions`다.
2026-09-02 운영 라우팅 probe 결과는 다음과 같다.

```text
POST /v1/gateway/chat/completions  -> 401, redirect 없음
POST /v1/gateway/chat/completions/ -> 401, redirect 없음
```

두 경로가 모두 같은 인증 단계에 도달하므로 끝 슬래시 없는 호출도 404나 redirect가 발생하지 않는다.

### 크레딧

PydanticAI 2.36.0의 OpenAI 사용량 변환은 Mindlogic의 임의 정밀도 문자열 소수 `usage.credits`를
기본 집계에 남기지 않는다. spike 어댑터는 원문을
`ModelResponse.provider_details["mindlogic_credits"]`에 문자열로 보존하고 기존 `_usage_decimal`로
복원한다. float 변환을 거치지 않는다.

### strict 스키마

PydanticAI가 만든 스키마에 `OpenAIJsonSchemaTransformer`를 먼저 적용하고 아래 키를 재귀 제거한다.

```text
maxItems, maxLength, maximum, minItems, minLength, minimum, pattern
```

전송 JSON에서 위 키가 전부 사라지고 `response_format.type=json_schema`, `strict=true`,
`additionalProperties=false`, 전체 property의 required 계약은 남았다. 현재 게이트웨이와 맞도록
`max_tokens`를 사용하고 `max_completion_tokens`는 보내지 않는다.

## 의존성과 위험

dev 환경에는 PydanticAI와 OpenAI SDK 등 전이 의존성이 추가됐지만 production 직접 의존성 5개는
바뀌지 않았다. 크레딧 보존은 `OpenAIChatModel`의 응답 처리 hook에 의존하므로 2.x 업그레이드 전에
spike 테스트를 먼저 실행해야 한다. PydanticAI의 자체 비용 모델이 있어도 예산 정본은 Mindlogic
credits와 Spring의 쿼터 예약·정산이다.

## 재현

```bash
cd agent
uv sync --locked
uv run pytest tests/test_pydantic_ai_spike.py -q
uv run ruff check spikes/pydantic_ai_spike.py tests/test_pydantic_ai_spike.py
```

기대 결과는 테스트 3건 통과와 ruff 오류 0건이다.

## P2-5 적용 체크리스트

- production dependency 전환 전 lockfile diff와 이미지 크기를 확인한다.
- provider 내부에 `OpenAIChatModel` + `mindlogic_model_profile()`을 둔다.
- `extract_mindlogic_credits()` 결과를 기존 `ProviderUsage.credits`에 넣는다.
- `retries=0`과 기존 provider/rate-limit retry 횟수 테스트를 유지한다.
- tool proposal/finish 유니온과 `maxSteps=3`은 P2-5에서 별도 검증한다.
- Spring이 실행·상태·감사·예산을 계속 소유한다.
