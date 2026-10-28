-- NULL preserves the absence of historical report-period evidence; never backfill from live issues.
ALTER TABLE news_reports ADD report_coverage_snapshot CLOB;
