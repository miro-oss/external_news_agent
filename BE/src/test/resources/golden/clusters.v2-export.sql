-- clusters.v2의 sourceArticleId 56건을 run 3862 당시 제목·본문으로 JSONL export한다.
-- 이후 run에서 갱신된 기사는 최초 후속 version이 보관한 직전 값을 사용한다.
-- 원문 JSONL은 로컬 측정 입력일 뿐 저장소에 커밋하지 않는다.
set pagesize 0
set feedback off
set verify off
set heading off
set echo off
set trimspool on
set long 1000000
set longchunksize 32767
set linesize 32767

alter session set container=FREEPDB1;

select json_object(
    'id' value a.id,
    'versioned' value case
        when exists (
            select 1
            from NEWS_AGENT.NEWS_ARTICLE_VERSIONS vx
            where vx.article_id = a.id
              and vx.run_id > 3862
        ) then 1
        else 0
    end,
    'title' value case
        when exists (
            select 1
            from NEWS_AGENT.NEWS_ARTICLE_VERSIONS vx
            where vx.article_id = a.id
              and vx.run_id > 3862
        ) then (
            select v.title
            from NEWS_AGENT.NEWS_ARTICLE_VERSIONS v
            where v.article_id = a.id
              and v.run_id = (
                  select min(vm.run_id)
                  from NEWS_AGENT.NEWS_ARTICLE_VERSIONS vm
                  where vm.article_id = a.id
                    and vm.run_id > 3862
              )
            fetch first 1 row only
        )
        else a.title
    end,
    'body' value case
        when exists (
            select 1
            from NEWS_AGENT.NEWS_ARTICLE_VERSIONS vx
            where vx.article_id = a.id
              and vx.run_id > 3862
        ) then (
            select v.body
            from NEWS_AGENT.NEWS_ARTICLE_VERSIONS v
            where v.article_id = a.id
              and v.run_id = (
                  select min(vm.run_id)
                  from NEWS_AGENT.NEWS_ARTICLE_VERSIONS vm
                  where vm.article_id = a.id
                    and vm.run_id > 3862
              )
            fetch first 1 row only
        )
        else a.body
    end
    returning clob
)
from NEWS_AGENT.NEWS_ARTICLES a
where a.id in (
    2455, 2456, 2476, 2445, 2449, 2519, 2520, 2521, 2312, 2326, 2327, 2450,
    2496, 1965, 2453, 2514, 2546, 2547, 2499, 2463, 2532, 2537, 1960, 2314,
    2333, 1867, 2382, 2415, 2548, 2344, 2345, 2368, 2389, 2390, 2392, 2403,
    2343, 2373, 2364, 2374, 2388, 2405, 2409, 2410, 2549, 2430, 2279, 2319,
    2352, 2354, 2366, 2394, 2399, 2412, 2426, 2416
)
order by a.id;

exit
