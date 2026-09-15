-- V49가 확인한 원문만 공유 저장소로 이동한다. 확인되지 않은 CLOB이 있으면 제거하지 않는다.
-- Oracle DDL은 자동 커밋된다. 첫 컬럼 제거 후 중단돼도 남아 있는 컬럼부터 재개할 수 있다.
DECLARE
    remaining NUMBER;
BEGIN
    FOR legacy IN (
        SELECT table_name FROM user_tab_columns
        WHERE table_name IN ('NEWS_ARTICLES', 'NEWS_ARTICLE_VERSIONS') AND column_name = 'BODY'
    ) LOOP
        EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM ' || legacy.table_name || ' WHERE body IS NOT NULL' INTO remaining;
        IF remaining > 0 THEN
            RAISE_APPLICATION_ERROR(-20001, 'Unmigrated bodies remain in ' || legacy.table_name);
        END IF;
    END LOOP;

    FOR legacy IN (
        SELECT table_name FROM user_tab_columns
        WHERE table_name IN ('NEWS_ARTICLES', 'NEWS_ARTICLE_VERSIONS') AND column_name = 'BODY'
    ) LOOP
        EXECUTE IMMEDIATE 'ALTER TABLE ' || legacy.table_name || ' DROP COLUMN body';
    END LOOP;
END;
/
