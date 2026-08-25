-- A8/M14 관점 태그와 로컬 PoC 단일 사용자의 기본 관점 설정.
ALTER TABLE news_findings ADD (
    perspective_tags CLOB,
    CONSTRAINT ck_finding_perspective_tags CHECK (perspective_tags IS JSON)
);

ALTER TABLE app_settings ADD (
    default_audience VARCHAR2(30) DEFAULT 'CHIP_MAKER' NOT NULL,
    CONSTRAINT ck_app_settings_audience CHECK (default_audience IN
        ('CHIP_MAKER', 'EQUIPMENT_MAKER', 'MARKET_INVESTOR', 'IT_INFRA'))
);
