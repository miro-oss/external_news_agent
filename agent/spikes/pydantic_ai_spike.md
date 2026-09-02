# P2-4 PydanticAI 도입 가능성 spike

측정일: 2026-09-02<br>
이슈: #136<br>
대상: `pydantic-ai-slim[openai] == 2.36.0`

## 결론

**P2-5 조사 루프에 PydanticAI를 조건부 도입한다.** 세 필수 계약이 모두 충족된다.
다만 기본 설정만으로는 현재 시스템과 호환되지 않으므로 아래 어댑터를 함께 적용해야 한다.

1. `OpenAIChatModel`을 사용하고 기본 Responses API 경로를 사용하지 않는다.
2. request hook으로 기존 `/chat/completions/` 끝 슬래시 계약을 강제한다.
3. `MindlogicUsageOpenAIChatModel`로 비표준 credits·cost usage를 보존한다.
4. `MindlogicStrictJsonSchemaTransformer`로 Mindlogic이 받지 않는 7개 키를 제거한다.
5. `NativeOutput(..., strict=True)`와 `retries=0`을 고정하고 streaming을 사용하지 않는다.
6. 기존 `AnalyzeProvider`, provider guard/retry/breaker, Spring 쿼터 예약·정산을 정본으로 유지한다.

P2-5에서 PydanticAI 의존성을 production dependency로 전환하고 위 어댑터를
`/v1/explore` PAID provider 경계에 연결했다.

## 측정 환경

- Python: 프로젝트 계약 `>=3.12` (로컬 실행 3.13)
- PydanticAI: `2.36.0` 고정
- OpenAI SDK: lockfile 기준 `3.7.0`
- httpx2: `2.12.0` production 직접 의존성 고정
- 검증 코드: `spikes/pydantic_ai_spike.py`, `tests/test_pydantic_ai_spike.py`
- 공식 근거:
  - [OpenAI-compatible model과 `base_url` 구성](https://pydantic.dev/docs/ai/models/openai/#openai-compatible-models)
  - [Chat Completions API 모델 선택](https://pydantic.dev/docs/ai/models/openai/#chat-completions-api)
  - [PydanticAI slim 2.36.0 배포 메타데이터](https://pypi.org/project/pydantic-ai-slim/2.36.0/)

실제 API 키와 실제 LLM 호출은 사용하지 않았다. 끝 슬래시 라우팅 probe도 무인증 요청이라
인증 이후의 동일 handler 도달 여부를 증명하지 못한다. 그래서 추론에 기대지 않고 request hook으로
현재 production provider가 사용하는 슬래시 포함 경로를 그대로 보존한다.

## 판정표

| 필수 확인 항목 | 측정 | 판정 |
|---|---|---|
| `/chat/completions/` 끝 슬래시 | OpenAI SDK가 만드는 `/chat/completions`를 request hook에서 기존 production 경로 `/chat/completions/`로 고정 | **PASS(request hook 필수)** |
| usage 추출 | 기본 `RunUsage.details`가 보존하지 않는 credits·cost 별칭 원문을 응답 hook에 모두 보존하고 기존 `Decimal` 계약으로 복원. `finish_reason=length`도 `truncated`로 변환 | **PASS(비스트리밍 어댑터 필수)** |
| strict 스키마 | 전용 transformer가 OpenAI strict 변환 **전** 기존 7개 비지원 키를 중첩 `$defs`까지 제거. 기존 description, `strict: true`, 모든 속성 required, `additionalProperties: false` 유지 | **PASS(전용 profile 필수)** |

추가로 `retries=0`에서 잘못된 JSON 응답이 들어오면 HTTP 요청이 정확히 1회만 발생함을 확인했다.
따라서 기존 rate-limit/provider retry와 PydanticAI retry가 중첩되지 않는다.

## 세부 결과

### 끝 슬래시

OpenAI SDK가 처음 만드는 URL은 `/chat/completions`지만 request hook 적용 후 자동 테스트가 캡처한
URL은 `https://gateway.test/v1/gateway/chat/completions/`다.
2026-09-02 운영 라우팅 probe 결과는 다음과 같다.

```text
POST /v1/gateway/chat/completions  -> 401, redirect 없음
POST /v1/gateway/chat/completions/ -> 401, redirect 없음
```

두 경로 모두 인증 전에는 404나 redirect가 발생하지 않았다. 그러나 이 결과만으로 인증 이후 같은
handler에 도달한다고 볼 수는 없다. 채택 구성에서는 `preserve_mindlogic_trailing_slash()`를 필수로
등록해 운영 provider의 검증된 경로 계약을 바꾸지 않는다.

### 사용량과 잘림 상태

PydanticAI 2.36.0의 OpenAI 사용량 변환은 Mindlogic의 비표준 credits·cost 필드를 기본 집계에
남기지 않는다. spike 어댑터는 모든 알려진 별칭을 `ModelResponse.provider_details`에 문자열로
보존하고 공용 `parse_usage_decimal()`로 첫 유효 값을 복원한다. 앞 별칭이 음수·비수치여도 다음
별칭으로 fallback한다. `finish_reason=length`는 기존 provider와 동일하게 `truncated=true`로 매핑한다.

JSON **문자열**로 온 소수는 `Decimal`로 정확히 보존한다. JSON **숫자**는 OpenAI SDK에 도달하기
전에 Python float 정밀도 제한을 받으므로 임의 정밀도를 보장하지 않는다. 두 입력 형태를 각각
테스트해 이 경계를 고정했다.

이 응답 hook은 `Agent.run()` 비스트리밍 경로에만 적용한다. P2-5에서는 `run_stream()`을 사용하지
않는다. 향후 streaming을 켜려면 streamed response의 usage 보존 어댑터와 계약 테스트를 먼저 추가한다.

### strict 스키마

아래 키를 중첩 `$defs`까지 먼저 재귀 제거한 뒤 PydanticAI의 `OpenAIJsonSchemaTransformer`를 적용한다.

```text
maxItems, maxLength, maximum, minItems, minLength, minimum, pattern
```

제거를 먼저 해야 상위 transformer가 제약을 description에 prompt hint로 주입하지 않는다. 전송
JSON에서 위 키가 전부 사라지고 기존 description은 그대로이며 `response_format.type=json_schema`,
`strict=true`, `additionalProperties=false`, 전체 property의 required 계약은 남았다. 현재
게이트웨이와 맞도록 `max_tokens`를 사용하고 `max_completion_tokens`는 보내지 않는다.

## 의존성과 위험

production 환경에 PydanticAI와 OpenAI SDK가 추가됐고 런타임에서 직접 import하는
`httpx2==2.12.0`도 고정했다. 별칭·스키마 정리·Decimal 변환은
production provider의 공용 경계를 재사용해 spike가 app private 구현에 결합하지 않는다.

사용량 보존은 불가피하게 PydanticAI의 private `_process_provider_details` hook에 의존한다. 따라서
PydanticAI 버전을 고정하고 2.x 업그레이드 전에 spike 계약 테스트를 먼저 실행해야 한다. 자체 비용
모델이 있어도 예산 정본은 Mindlogic credits와 Spring의 쿼터 예약·정산이다.

## 재현

```bash
cd agent
uv sync --locked
uv run pytest tests/test_pydantic_ai_spike.py -q
uv run ruff check spikes/pydantic_ai_spike.py tests/test_pydantic_ai_spike.py
```

기대 결과는 테스트 6건 통과와 ruff 오류 0건이다.

## P2-5 적용 체크리스트

- production dependency 전환 전 lockfile diff와 이미지 크기를 확인한다.
- provider 내부에 `MindlogicUsageOpenAIChatModel` + `mindlogic_model_profile()`을 둔다.
- `httpx2.AsyncClient` request hook에 `preserve_mindlogic_trailing_slash()`를 등록한다.
- `extract_mindlogic_credits()`와 `extract_mindlogic_cost_usd()`를 기존 `ProviderUsage`에 넣고
  `is_mindlogic_truncated()`를 `ProviderResponse.truncated`에 넣는다.
- `Agent.run()`만 사용한다. streaming은 별도 usage 어댑터와 테스트 전에는 활성화하지 않는다.
- `retries=0`과 기존 provider/rate-limit retry 횟수 테스트를 유지한다.
- tool proposal/finish 유니온과 `maxSteps=3`은 P2-5에서 별도 검증한다.
- Spring이 실행·상태·감사·예산을 계속 소유한다.
