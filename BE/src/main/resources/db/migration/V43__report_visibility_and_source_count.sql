-- 보고서 본문과 기사·발송 이력 참조는 유지하고 제품 화면에서만 제외한다.
ALTER TABLE news_reports ADD (
    deleted_at TIMESTAMP,
    source_report_count NUMBER(10)
);

-- 일일 통합 당시 실제로 생성된 RUN 보고서만 센다. 실행 수를 보고서 수로 간주하지 않는다.
UPDATE news_reports daily
SET source_report_count = (
    SELECT COUNT(*)
    FROM news_reports source
    WHERE source.report_scope = 'RUN'
      AND source.report_status <> 'PENDING'
      AND source.generated_at <= daily.generated_at
      AND source.run_id IN (
          SELECT selected.run_id
          FROM JSON_TABLE(daily.source_run_ids, '$[*]'
              COLUMNS (run_id NUMBER PATH '$')) selected
      )
)
WHERE daily.report_scope = 'DAILY';

ALTER TABLE news_reports ADD CONSTRAINT ck_report_source_count
    CHECK (source_report_count >= 0);
