-- SQL*Plus, read-only. Arguments: as-of KST (YYYY-MM-DDTHH24:MI:SS), lookback hours.
-- Application timestamps are Asia/Seoul local TIMESTAMP, not database UTC.
-- Example: @independent-readiness.sql 2026-09-04T13:10:00 48
whenever sqlerror exit failure rollback
set pagesize 0 feedback off heading off verify off echo off trimspool on
set linesize 32767 long 1000000 longchunksize 32767
alter session set container=FREEPDB1;
set transaction read only;

select json_object(
    'kind' value 'topic', 'topicId' value t.id, 'name' value t.name,
    'active' value t.active_yn, 'intervalMinutes' value t.interval_minutes,
    'runCount' value count(distinct r.id),
    'successRunCount' value count(distinct case when r.status = 'SUCCESS' then r.id end),
    'partialRunCount' value count(distinct case when r.status = 'PARTIAL' then r.id end),
    'uniqueArticles' value count(distinct o.article_id),
    'firstStartedAt' value to_char(min(r.started_at), 'YYYY-MM-DD"T"HH24:MI:SS') || '+09:00',
    'lastStartedAt' value to_char(max(r.started_at), 'YYYY-MM-DD"T"HH24:MI:SS') || '+09:00'
)
from NEWS_AGENT.news_collection_runs r
join NEWS_AGENT.news_collection_run_items i on i.run_id = r.id
join NEWS_AGENT.news_topics t on t.id = i.topic_id
left join NEWS_AGENT.news_collection_run_articles o on o.run_id = r.id and o.topic_id = i.topic_id
where r.trigger_type = 'SCHEDULED' and r.status in ('SUCCESS', 'PARTIAL')
  and r.finished_at is not null
  and r.started_at >= to_timestamp('&1', 'YYYY-MM-DD"T"HH24:MI:SS') - numtodsinterval(&2, 'HOUR')
  and r.finished_at <= to_timestamp('&1', 'YYYY-MM-DD"T"HH24:MI:SS')
group by t.id, t.name, t.active_yn, t.interval_minutes
order by t.id;

select json_object(
    'kind' value 'run', 'runId' value r.id, 'triggerType' value r.trigger_type,
    'status' value r.status,
    'startedAt' value to_char(r.started_at, 'YYYY-MM-DD"T"HH24:MI:SS') || '+09:00',
    'finishedAt' value to_char(r.finished_at, 'YYYY-MM-DD"T"HH24:MI:SS') || '+09:00',
    'scannedCount' value r.scanned_count, 'newCount' value r.new_count,
    'updatedCount' value r.updated_count
)
from NEWS_AGENT.news_collection_runs r
where r.started_at >= to_timestamp('&1', 'YYYY-MM-DD"T"HH24:MI:SS') - numtodsinterval(&2, 'HOUR')
  and r.started_at <= to_timestamp('&1', 'YYYY-MM-DD"T"HH24:MI:SS')
order by r.id;
rollback;
exit
