-- 무료 기본 검색원인 NAVER를 여러 페이지 읽어 1회 수집 후보를 100~300건까지 넓힌다(#96).
ALTER TABLE news_topics MODIFY (batch_size DEFAULT 100);

-- V2의 기존 제약 이름을 유지해 애플리케이션과 운영 문서에서 같은 이름으로 식별할 수 있게 한다.
ALTER TABLE news_topics DROP CONSTRAINT ck_topic_batch_size;
ALTER TABLE news_topics ADD CONSTRAINT ck_topic_batch_size CHECK (batch_size BETWEEN 1 AND 300);
