-- URL/이슈 유사도와 독립된 원문 저장소. 동일한 UTF-8 본문만 공유한다.
CREATE TABLE news_article_bodies (
    body_hash VARCHAR2(64 CHAR) PRIMARY KEY,
    body CLOB NOT NULL
);

ALTER TABLE news_articles ADD body_hash VARCHAR2(64 CHAR);
ALTER TABLE news_articles ADD CONSTRAINT fk_article_body
    FOREIGN KEY (body_hash) REFERENCES news_article_bodies (body_hash);
CREATE INDEX ix_article_body ON news_articles (body_hash);

ALTER TABLE news_article_versions ADD body_hash VARCHAR2(64 CHAR);
ALTER TABLE news_article_versions ADD CONSTRAINT fk_article_version_body
    FOREIGN KEY (body_hash) REFERENCES news_article_bodies (body_hash);
CREATE INDEX ix_article_version_body ON news_article_versions (body_hash);
