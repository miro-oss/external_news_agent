-- 기존 주기와 24시간 기본값을 유지하고, 0으로 정기 수집만 끌 수 있게 한다.
ALTER TABLE news_topics DROP CONSTRAINT ck_topic_interval;
ALTER TABLE news_topics ADD CONSTRAINT ck_topic_interval
    CHECK (interval_minutes IN (0, 60, 720, 1440, 10080));
