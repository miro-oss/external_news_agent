# External News Agent

Spring Boot가 내부 HTTP로 호출하는 stateless FastAPI 에이전트입니다. 기사 분석(`/v1/analyze`)과
run 보고서 작성(`/v1/report`)을 제공하며, 기본 Mock 모드에서는 외부 LLM 없이 결정적인 결과를
반환합니다.

```bash
uv sync --frozen
AGENT_SHARED_SECRET=local-dev-agent-token uv run uvicorn app.main:app \
  --host 127.0.0.1 --port 8088
```

보고서는 기사 1건 분석과 별도로 `AGENT_REPORT_MAX_OUTPUT_TOKENS`(기본 8192)와
`AGENT_REPORT_PROVIDER_TIMEOUT_SECONDS`(기본 120초)를 사용합니다. 한 요청에는 우선순위가 높은
LLM finding을 최대 50건까지 받습니다.

실제 provider 호출은 공통 circuit breaker와 동시성 guard를 통과합니다. 기본값은 동시 호출 4건,
연속 실패 3회 시 30초 open이며 모두 환경변수(`AGENT_PROVIDER_CONCURRENCY`,
`AGENT_CIRCUIT_FAILURE_THRESHOLD`, `AGENT_CIRCUIT_COOLDOWN_SECONDS`)로 조정합니다. Mindlogic 응답에
credits 사용량이 없으면 `MINDLOGIC_CREDITS_PER_REQUEST`(기본 1)를 보수적인 환산값으로 사용합니다.

검증 명령은 다음과 같습니다.

```bash
uv run ruff check .
uv run pytest
```

실제 시크릿이나 provider API 키는 파일에 저장하지 않고 환경변수로만 전달합니다.
