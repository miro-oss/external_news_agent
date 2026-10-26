-- DART also publishes listed stock codes containing uppercase Latin letters.
-- Keep V68 immutable so existing installations can upgrade safely.
ALTER TABLE news_dart_companies DROP CONSTRAINT ck_dart_stock_code;
ALTER TABLE news_dart_companies ADD CONSTRAINT ck_dart_stock_code
    CHECK(stock_code IS NULL OR REGEXP_LIKE(stock_code, '^[0-9A-Z]{6}$', 'c'));
