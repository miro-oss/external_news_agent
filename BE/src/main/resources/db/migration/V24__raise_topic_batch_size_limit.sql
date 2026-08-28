-- 무료 기본 검색원인 NAVER를 여러 페이지 읽어 1회 수집 후보를 100~300건까지 넓힌다(#96).
ALTER TABLE news_topics MODIFY (batch_size DEFAULT 100);

-- V2의 기존 제약 이름을 유지해 애플리케이션과 운영 문서에서 같은 이름으로 식별할 수 있게 한다.
ALTER TABLE news_topics DROP CONSTRAINT ck_topic_batch_size;
ALTER TABLE news_topics ADD CONSTRAINT ck_topic_batch_size CHECK (batch_size BETWEEN 1 AND 300);

-- V18과 같은 방식으로 직전 기본값인 20을 새 기본값으로 올린다.
-- 명시적으로 20을 저장한 행과 옛 기본값을 구분할 이력이 없으므로, 다른 값은 보존하고 20만 변경한다.
UPDATE news_topics SET batch_size = 100 WHERE batch_size = 20;
