-- SQL*Plus read-only, NO issue memberships, content groups, scores or predictions.
-- Args: as-of KST, lookback hours, earliest first-seen KST, topic ID 1, topic ID 2.
-- Exports the CURRENT source text in one consistent transaction. This is NOT an
-- exact historical replay: news_article_versions does not retain old summaries.
-- Preserve output outside Git; NLS_LANG=AMERICAN_AMERICA.AL32UTF8 is required.
-- Each line: topicId:sourceArticleId|chunkNumber|JSON fragment (<=900 chars)|END
-- The sentinel prevents SQL*Plus trimspool from removing source whitespace.
-- 900 characters also fit SQL VARCHAR2(4000) with four-byte UTF-8 characters.
-- Join chunks before JSON parsing; never truncate FULLTEXT to fixture fragments.
whenever sqlerror exit failure rollback
set pagesize 0 feedback off heading off verify off echo off trimspool on
set linesize 32767 long 1000000 longchunksize 32767
alter session set container=FREEPDB1;
set transaction read only;

with eligible_observations as (
    select o.topic_id, o.article_id, r.id run_id, min(o.observed_at) observed_at
    from NEWS_AGENT.news_collection_run_articles o
    join NEWS_AGENT.news_collection_runs r on r.id = o.run_id
    join NEWS_AGENT.news_articles a on a.id = o.article_id
    join NEWS_AGENT.news_collection_runs first_run on first_run.id = a.first_seen_run_id
    where o.topic_id in (&4, &5)
      and r.trigger_type = 'SCHEDULED' and r.status in ('SUCCESS', 'PARTIAL')
      and r.finished_at is not null
      and r.started_at >= to_timestamp('&1', 'YYYY-MM-DD"T"HH24:MI:SS') - numtodsinterval(&2, 'HOUR')
      and r.finished_at <= to_timestamp('&1', 'YYYY-MM-DD"T"HH24:MI:SS')
      and first_run.started_at >= to_timestamp('&3', 'YYYY-MM-DD"T"HH24:MI:SS')
    group by o.topic_id, o.article_id, r.id
), provenance as (
    select topic_id, article_id, min(run_id) source_run_id,
           min(observed_at) observed_at,
           json_arrayagg(run_id order by run_id returning clob) source_run_ids
    from eligible_observations group by topic_id, article_id
), payloads as (
    select p.topic_id || ':' || a.id row_key,
           json_object(
               'sourceArticleId' value a.id, 'sourceRunId' value p.source_run_id,
               'sourceRunIds' value p.source_run_ids format json,
               'topicId' value p.topic_id, 'title' value a.title,
               'summary' value a.summary, 'body' value a.body,
               'fetchStatus' value a.fetch_status, 'sourceId' value a.source_id,
               'publisher' value coalesce(a.source_name, s.name),
               'reliabilityScore' value s.reliability_score,
               'publishedAt' value to_char(a.published_at, 'YYYY-MM-DD"T"HH24:MI:SSTZH:TZM'),
               'observedAt' value to_char(coalesce(a.collected_at, p.observed_at), 'YYYY-MM-DD"T"HH24:MI:SS') || '+09:00',
               'topicRequiredKeywords' value t.required_keywords format json,
               'topicOptionalKeywords' value t.optional_keywords format json,
               'topicQueryText' value t.query_text,
               'snapshotAt' value to_char(systimestamp at time zone 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SSTZH:TZM')
               returning clob
           ) payload
    from provenance p
    join NEWS_AGENT.news_articles a on a.id = p.article_id
    join NEWS_AGENT.news_topics t on t.id = p.topic_id
    join NEWS_AGENT.news_sources s on s.id = a.source_id
)
select p.row_key || '|' || chunks.n || '|' || dbms_lob.substr(p.payload, 900, (chunks.n - 1) * 900 + 1) || '|END'
from payloads p
cross apply (select level n from dual connect by level <= ceil(dbms_lob.getlength(p.payload) / 900)) chunks
order by p.row_key, chunks.n;
rollback;
exit
