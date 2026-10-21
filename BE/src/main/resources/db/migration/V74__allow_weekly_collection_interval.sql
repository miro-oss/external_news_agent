-- 기존 주기와 24시간 기본값은 유지하고, 1주(10080분) 선택지만 추가한다.
ALTER TABLE news_topics DROP CONSTRAINT ck_topic_interval;
ALTER TABLE news_topics ADD CONSTRAINT ck_topic_interval
    CHECK (interval_minutes IN (60, 720, 1440, 10080));
