-- Identity lookup must use the same Java NFKC normalization for the entire corpus.
-- Existing rows intentionally remain unavailable for automatic matching until the next atomic catalog sync.
ALTER TABLE news_dart_companies ADD identity_name VARCHAR2(4000 BYTE);
CREATE INDEX idx_dart_company_identity_name ON news_dart_companies (identity_name);
