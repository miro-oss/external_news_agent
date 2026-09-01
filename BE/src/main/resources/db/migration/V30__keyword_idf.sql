CREATE TABLE news_keyword_idf (
    language_code      VARCHAR2(5) NOT NULL,
    keyword_value      VARCHAR2(500) NOT NULL,
    document_count     NUMBER(10) NOT NULL,
    document_frequency NUMBER(10) NOT NULL,
    idf_value          NUMBER(19, 10) NOT NULL,
    refreshed_at       TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_news_keyword_idf PRIMARY KEY (language_code, keyword_value),
    CONSTRAINT ck_keyword_idf_counts CHECK (
        document_count >= 0
        AND document_frequency >= 0
        AND document_frequency <= document_count
    ),
    CONSTRAINT ck_keyword_idf_value CHECK (idf_value >= 1)
);

CREATE INDEX ix_keyword_idf_refreshed_at ON news_keyword_idf (refreshed_at);
