# External News Agent

Spring Boot가 내부 HTTP로 호출하는 stateless FastAPI 에이전트입니다. A0에서는 외부 LLM 없이
결정적인 Mock 분석 결과를 반환합니다.

```bash
uv sync --frozen
AGENT_SHARED_SECRET=local-dev-agent-token uv run uvicorn app.main:app \
  --host 127.0.0.1 --port 8088
```

검증 명령은 다음과 같습니다.

```bash
uv run ruff check .
uv run pytest
```

실제 시크릿이나 provider API 키는 파일에 저장하지 않고 환경변수로만 전달합니다.
