-- Corporate identifiers are public data; credentials are never persisted here.
CREATE TABLE news_dart_companies (
    corp_code VARCHAR2(8 CHAR) PRIMARY KEY,
    corp_name VARCHAR2(512 CHAR) NOT NULL,
    stock_code VARCHAR2(6 CHAR),
    modified_at DATE NOT NULL,
    CONSTRAINT ck_dart_corp_code CHECK(REGEXP_LIKE(corp_code, '^[0-9]{8}$')),
    CONSTRAINT ck_dart_stock_code CHECK(stock_code IS NULL OR REGEXP_LIKE(stock_code, '^[0-9]{6}$'))
);
CREATE INDEX ix_dart_company_name ON news_dart_companies(corp_name);
CREATE INDEX ix_dart_company_stock ON news_dart_companies(stock_code);
