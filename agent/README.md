# External News Agent

Spring Boot가 내부 HTTP로 호출하는 stateless FastAPI 에이전트입니다. 기사 분석(`/v1/analyze`)과
run 보고서 작성(`/v1/report`)을 제공하며, 기본 Mock 모드에서는 외부 LLM 없이 결정적인 결과를
반환합니다. 근거 검증(`/v1/verify-evidence`)은 숫자·날짜·기업명 왜곡을 먼저 차단하고,
근거 연결 상태를 `grounded` / `weak` / `ungrounded`로 반환합니다.

```bash
uv sync --frozen
AGENT_SHARED_SECRET=local-dev-agent-token uv run uvicorn app.main:app \
  --host 127.0.0.1 --port 8088
```

보고서는 기사 1건 분석과 별도로 `AGENT_REPORT_MAX_OUTPUT_TOKENS`(기본 8192)와
`AGENT_REPORT_PROVIDER_TIMEOUT_SECONDS`(기본 120초)를 사용합니다. 한 요청에는 우선순위가 높은
LLM finding을 최대 50건까지 받습니다.

Mock 근거 검증의 어휘 겹침 임계값은 `AGENT_EVIDENCE_GROUNDED_OVERLAP`(기본 0.6)과
`AGENT_EVIDENCE_WEAK_OVERLAP`(기본 0.2)로 조정할 수 있습니다.
근거 검증 입력 상한은 `AGENT_EVIDENCE_MAX_CLAIM_CHARS`(기본 2,000),
`AGENT_EVIDENCE_MAX_SENTENCES`(기본 50), `AGENT_EVIDENCE_MAX_TOTAL_CHARS`(기본 40,000)입니다.

실제 provider 호출은 공통 circuit breaker와 동시성 guard를 통과합니다. 기본값은 동시 호출 4건,
연속 실패 3회 시 30초 open이며 모두 환경변수(`AGENT_PROVIDER_CONCURRENCY`,
`AGENT_CIRCUIT_FAILURE_THRESHOLD`, `AGENT_CIRCUIT_COOLDOWN_SECONDS`)로 조정합니다. Mindlogic 응답에
credits 사용량이 없으면 `MINDLOGIC_CREDITS_PER_REQUEST`(기본 1)를 보수적인 환산값으로 사용합니다.

검증 명령은 다음과 같습니다.

```bash
uv run ruff check .
uv run pytest
```

## Golden eval

`app/eval/golden/semiconductor.v1.json`은 한국어·영어 반도체 기사 24건과
`analyze.ko.v1` replay 출력을 담습니다. 기본 평가는 외부 API를 호출하지 않고 분석과 보고서의 실제
스키마 검증 경로를 실행합니다.

```bash
uv run python -m app.eval --profile replay \
  --compare app/eval/golden/analyze.ko.v1.baseline.json
```

평가 결과에는 schema pass rate, grounded rate, unsupported report claim count,
Korean summary pass rate가 포함됩니다. `--output result.json`으로 결과를 남긴 뒤 다른 프롬프트 버전의
결과와 비교할 수 있습니다. 기본 CI도 replay 기준선만 사용하며 회귀가 있으면 실패합니다.

- schema pass rate: 분석 24건과 보고서 1건의 계약 검증 통과율
- grounded rate: 분석 bullet 중 `grounded` 판정 비율 (`weak`은 포함하지 않음)
- unsupported report claim count: 보고서 주장을 연결된 finding 요약·핵심 포인트와 기존 A4 규칙으로
  검증했을 때 `ungrounded`인 개수
- Korean summary pass rate: 한글 5자 이상이며 한글·영문 문자 중 한글 비율이 50% 이상인 요약 비율

실제 LLM 평가는 provider 환경변수를 설정한 뒤 수동으로만 실행합니다. 전체 골든셋 분석 24회와
보고서 1회 호출이 발생하므로 사용량을 확인한 뒤 실행해야 합니다.

```bash
uv run python -m app.eval --profile live --plan FREE --output live-result.json
```

실제 시크릿이나 provider API 키는 파일에 저장하지 않고 환경변수로만 전달합니다.
