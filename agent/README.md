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
`analyze.ko.v1` replay 출력을 담습니다. 수치 오기, 기업명 바꿔치기, 부정 반전, 영문 요약은
`expectedFailures`로 명시해 규칙이 지나치게 엄격해지거나 느슨해지는 회귀를 함께 잡습니다.
`report.ko.v1.json`은 finding과 독립된 버전 보고서 fixture이며 grounded·weak·ungrounded 주장 기대값을
각각 가집니다.

```bash
uv run python -m app.eval --profile replay \
  --compare app/eval/golden/analyze.ko.v1.baseline.json
```

replay는 외부 API 없이 실제 스키마·문장 분할·사실값 검증·보고서 claim scorer를 실행하는
**계약/규칙 회귀 하네스**입니다. 저장된 출력을 재생하므로 프롬프트 생성 품질을 측정하지는 않습니다.
기본 CI는 replay 기준선만 사용하며 메타데이터, 런타임 설정, 지표 또는 평가 커버리지가 회귀하면
실패합니다.

- schema pass rate: 분석 24건과 보고서 1건의 계약 검증 통과율
- grounded rate: 분석 bullet 중 `grounded` 판정 비율 (`weak`은 포함하지 않음)
- report weak/unsupported claim count: 보고서 주장을 연결된 finding 요약·핵심 포인트와 기존 A4
  규칙으로 검증했을 때 각각 `weak`/`ungrounded`인 개수. executive summary는 전체 finding을 합치지
  않고 가장 잘 맞는 단일 finding으로 판정
- Korean summary pass rate: 한글 5자 이상이며 한글·영문 문자 중 한글 비율이 50% 이상인 요약 비율

실제 프롬프트 품질은 provider 환경변수를 설정한 뒤 live 프로필로 수동 평가합니다. 결과에는 모델 id,
근거 임계값, 문장 상한, schema repair 횟수와 출력 토큰 상한이 기록됩니다. 전체 골든셋 분석 24회와
보고서 1회 호출이 발생하므로 사용량을 확인한 뒤 실행해야 합니다.

FREE profile은 기본 13초, PAID profile은 기본 1초의 provider 호출 간격을 둡니다. 429 응답은 provider
응답의 retry delay 또는 15~60초 exponential backoff를 사용해 최대 5회 재시도하며, 429를 provider
장애 circuit에 누적하지 않습니다. 간격과 재시도 정책은 CLI 옵션으로 결과의 `config.livePolicy`에
함께 기록됩니다.

성공한 분석은 checkpoint에 즉시 저장할 수 있습니다. 중간 실패 결과는 `complete: false`와 exit 1로
표시되며 보고서를 생성하지 않습니다. 429 재시도를 모두 소진하거나 인증·요청 오류가 확인되면 남은
호출을 중단해 quota를 낭비하지 않고, 이후 `--resume`에서 미완료 case부터 다시 시작합니다.

```bash
uv run python -m app.eval --profile live --plan FREE \
  --checkpoint live-v1-free.checkpoint.json \
  --output live-v1-free.json
```

중단된 동일 실행은 dataset, prompt, plan, model, 판정 설정, 호출 정책이 모두 같을 때만 이어서
실행됩니다. 이미 성공한 분석은 provider를 다시 호출하지 않습니다.

```bash
uv run python -m app.eval --profile live --plan FREE \
  --checkpoint live-v1-free.checkpoint.json --resume \
  --output live-v1-free.json
```

프로젝트의 실제 한도에 맞춰 간격을 바꿔야 한다면 `--request-interval-seconds`를 사용합니다. 재개 시에는
checkpoint에 기록된 값과 동일해야 합니다.

동일 데이터셋·모델·런타임 설정에서 프롬프트 버전만 의도적으로 바꿔 비교할 때는 live 프로필에 한해
명시적 override를 사용합니다. 그 외 dataset/profile/plan/config 불일치는 비교 오류로 중단됩니다.

```bash
uv run python -m app.eval --profile live --plan FREE \
  --request-interval-seconds 13 \
  --compare live-v1-result.json --allow-prompt-version-change \
  --output live-v2-result.json
```

실제 시크릿이나 provider API 키는 파일에 저장하지 않고 환경변수로만 전달합니다.
