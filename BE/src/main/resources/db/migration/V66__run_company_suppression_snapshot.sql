-- Generator coverage is not a company-selection allowlist. Keep actual exclusions separately.
ALTER TABLE news_reports ADD company_suppressed_finding_ids CLOB DEFAULT '[]' NOT NULL;
ALTER TABLE news_reports ADD CONSTRAINT ck_report_company_suppressed CHECK(company_suppressed_finding_ids IS JSON);

-- V65 recorded selection and application receipts atomically. No positive suppression receipt
-- means no company exclusions, so the default [] restores uncovered STUB/over-limit findings.
-- For older RUNs with actual suppression, completion already discarded the full selected input.
-- Preserve their previous visibility conservatively; do not guess which missing IDs were suppressed.
UPDATE news_reports report
SET company_suppressed_finding_ids = (
    SELECT COALESCE(JSON_ARRAYAGG(finding.id ORDER BY finding.id RETURNING CLOB), TO_CLOB('[]'))
    FROM news_findings finding
    WHERE finding.run_id = report.run_id
      AND NOT EXISTS (
          SELECT 1 FROM JSON_TABLE(report.report_reflected_finding_ids, '$[*]'
              COLUMNS(finding_id NUMBER PATH '$')) reflected
          WHERE reflected.finding_id = finding.id)
      AND NOT EXISTS (
          SELECT 1 FROM JSON_TABLE(report.report_excluded_finding_ids, '$[*]'
              COLUMNS(finding_id NUMBER PATH '$')) excluded
          WHERE excluded.finding_id = finding.id)
)
WHERE report.report_scope = 'RUN' AND report.company_selection_recorded_yn = 'Y'
  AND EXISTS (SELECT 1 FROM news_company_interest_apps application
              WHERE application.report_id = report.id AND application.suppressed_count > 0);
