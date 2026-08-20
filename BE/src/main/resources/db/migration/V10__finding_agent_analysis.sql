-- A1 구조화 분석과 실제 분석 경로를 finding 자체에서도 추적한다.
-- 기존 M4/A0 finding은 Stub 결과였으므로 STUB으로 마이그레이션한다.
ALTER TABLE news_findings ADD (
    analysis_source   VARCHAR2(20) DEFAULT 'STUB' NOT NULL,
    analysis_sections CLOB DEFAULT '[]' NOT NULL,
    entities          CLOB DEFAULT '{"companies":[],"products":[],"technologies":[]}' NOT NULL,
    prompt_version    VARCHAR2(50),
    llm_provider      VARCHAR2(30),
    llm_model         VARCHAR2(100),
    input_tokens      NUMBER,
    output_tokens     NUMBER,
    cost_usd          NUMBER(12, 6),
    credits           NUMBER(10, 3),
    input_truncated   VARCHAR2(1) DEFAULT 'N' NOT NULL
);

ALTER TABLE news_findings ADD CONSTRAINT ck_finding_analysis_source
    CHECK (analysis_source IN ('STUB', 'LLM', 'REUSED'));
ALTER TABLE news_findings ADD CONSTRAINT ck_finding_analysis_sections_json
    CHECK (analysis_sections IS JSON);
ALTER TABLE news_findings ADD CONSTRAINT ck_finding_entities_json
    CHECK (entities IS JSON);
ALTER TABLE news_findings ADD CONSTRAINT ck_finding_input_truncated
    CHECK (input_truncated IN ('Y', 'N'));
