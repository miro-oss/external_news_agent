-- A2 Agent 보고서 생성 메타. 공개 보고서 API 계약은 바꾸지 않고 감사·비용 추적만 보강한다.
ALTER TABLE news_reports ADD (
    prompt_version VARCHAR2(50),
    llm_provider   VARCHAR2(30),
    input_tokens   NUMBER,
    output_tokens  NUMBER,
    cost_usd       NUMBER(12, 6),
    credits        NUMBER(10, 3),
    report_status  VARCHAR2(30) DEFAULT 'GENERATED' NOT NULL
);

ALTER TABLE news_reports ADD CONSTRAINT ck_news_report_status
    CHECK (report_status IN ('GENERATED', 'FALLBACK'));
