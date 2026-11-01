-- Legacy reports have no frozen company identity evidence and must not be backfilled on read.
ALTER TABLE news_reports ADD report_issue_company_snapshot CLOB;
