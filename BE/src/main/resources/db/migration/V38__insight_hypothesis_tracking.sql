-- A12. 인사이트 가설의 결정적 엔티티와 후속 기사 연결을 저장한다.
ALTER TABLE news_insights ADD (
    watch_entities_json       CLOB DEFAULT '[]' NOT NULL,
    input_article_ids_json    CLOB DEFAULT '[]' NOT NULL,
    related_article_ids_json  CLOB DEFAULT '[]' NOT NULL,
    CONSTRAINT ck_insight_watch_entities_json CHECK (watch_entities_json IS JSON),
    CONSTRAINT ck_insight_input_ids_json CHECK (input_article_ids_json IS JSON),
    CONSTRAINT ck_insight_related_ids_json CHECK (related_article_ids_json IS JSON)
);
