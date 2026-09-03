-- P2-9부터 자동 수집 주기는 1시간, 12시간, 24시간으로만 운영한다.
-- 기존 값을 아래쪽 표준 주기로 올려, 활성 주제가 예고 없이 수집 대상에서 빠지지 않게 한다.
UPDATE news_topics
SET interval_minutes = CASE
    WHEN interval_minutes < 60 THEN 60
    WHEN interval_minutes < 720 THEN 720
    ELSE 1440
END
WHERE interval_minutes NOT IN (60, 720, 1440);

ALTER TABLE news_topics DROP CONSTRAINT ck_topic_interval;
ALTER TABLE news_topics ADD CONSTRAINT ck_topic_interval
    CHECK (interval_minutes IN (60, 720, 1440));
