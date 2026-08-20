-- 이미 V10을 적용한 로컬 DB의 데이터와 Flyway 체크섬을 보존하면서
-- Y/N 컬럼 이름과 Oracle 타입을 프로젝트 규칙에 맞춘다.
ALTER TABLE news_findings ADD (
    input_truncated_yn CHAR(1) DEFAULT 'N'
);

UPDATE news_findings
SET input_truncated_yn = input_truncated;

ALTER TABLE news_findings MODIFY (
    input_truncated_yn DEFAULT 'N' NOT NULL
);

ALTER TABLE news_findings DROP CONSTRAINT ck_finding_input_truncated;
ALTER TABLE news_findings DROP COLUMN input_truncated;

ALTER TABLE news_findings ADD CONSTRAINT ck_finding_input_truncated_yn
    CHECK (input_truncated_yn IN ('Y', 'N'));
