-- Read-only; run as the application schema. Timestamps are stored in Asia/Seoul.
-- A row is a persisted create-request observation, not an audience card or provider attempt.
-- FULL/(FULL+PARTIAL+MISS) is the instrumented create-request cache ratio.
-- SUM(cached_audiences)/SUM(requested_audiences) is audience reuse, not token savings.
-- Historical NULL payloads are UNINSTRUMENTED, never inferred as MISS or HISTORY=0.
-- FULL rows have llm_plan=NULL and consume no legacy FREE quota. GET/rejected requests are excluded.
WITH observations AS (
    SELECT started_at, status, input_tokens, output_tokens, cost_usd,
           JSON_VALUE(action_payload, '$.schemaVersion' RETURNING NUMBER NULL ON ERROR) AS version,
           JSON_VALUE(action_payload, '$.type' NULL ON ERROR) AS observation_type,
           JSON_VALUE(action_payload, '$.cacheOutcome' NULL ON ERROR) AS cache_outcome,
           JSON_VALUE(action_payload, '$.usageCompleteness' NULL ON ERROR) AS usage_completeness,
           JSON_VALUE(action_payload, '$.requestedAudienceCount' RETURNING NUMBER NULL ON ERROR) AS requested,
           JSON_VALUE(action_payload, '$.cachedAudienceCount' RETURNING NUMBER NULL ON ERROR) AS reused
    FROM agent_runs
    WHERE agent_task = 'INSIGHT'
      AND started_at >= CAST(SYSTIMESTAMP AT TIME ZONE 'Asia/Seoul' AS TIMESTAMP) - INTERVAL '7' DAY
)
SELECT TO_CHAR(TRUNC(started_at), 'YYYY-MM-DD') AS observation_day, status,
       CASE WHEN version = 1 AND observation_type = 'INSIGHT_OBSERVATION'
            THEN cache_outcome ELSE 'UNINSTRUMENTED' END AS cache_outcome,
       COUNT(*) AS request_rows,
       SUM(requested) AS requested_audiences, SUM(reused) AS cached_audiences,
       SUM(input_tokens) AS known_input_tokens, SUM(output_tokens) AS known_output_tokens,
       SUM(cost_usd) AS known_cost_usd,
       SUM(CASE WHEN input_tokens IS NULL OR output_tokens IS NULL OR cost_usd IS NULL
                THEN 1 ELSE 0 END) AS unknown_usage_rows,
       SUM(CASE WHEN usage_completeness = 'PARTIAL' THEN 1 ELSE 0 END) AS partial_usage_rows,
       SUM(CASE WHEN usage_completeness = 'UNKNOWN' OR usage_completeness IS NULL
                THEN 1 ELSE 0 END) AS unverified_usage_rows
FROM observations
GROUP BY TRUNC(started_at), status,
         CASE WHEN version = 1 AND observation_type = 'INSIGHT_OBSERVATION'
              THEN cache_outcome ELSE 'UNINSTRUMENTED' END
ORDER BY observation_day, status, cache_outcome;

-- Separate failed/successful, observed/configured, cache outcomes and input cohorts.
-- NULL sums mean no known usage; known sums may still be incomplete after provider errors.
WITH issued AS (
    SELECT status, llm_provider, llm_model, prompt_version, input_tokens, output_tokens, cost_usd,
           JSON_VALUE(action_payload, '$.metadataSource' NULL ON ERROR) AS metadata_source,
           JSON_VALUE(action_payload, '$.usageCompleteness' NULL ON ERROR) AS usage_completeness,
           JSON_VALUE(action_payload, '$.configuredModel' NULL ON ERROR) AS configured_model,
           JSON_VALUE(action_payload, '$.expectedPromptVersion' NULL ON ERROR) AS expected_prompt,
           JSON_VALUE(action_payload, '$.cacheOutcome' NULL ON ERROR) AS cache_outcome,
           JSON_VALUE(action_payload, '$.submittedCurrentFindingCount' RETURNING NUMBER NULL ON ERROR) AS current_count,
           JSON_VALUE(action_payload, '$.submittedHistoryFindingCount' RETURNING NUMBER NULL ON ERROR) AS history_count
    FROM agent_runs
    WHERE agent_task = 'INSIGHT' AND status IN ('SUCCESS', 'FAILED')
      AND started_at >= CAST(SYSTIMESTAMP AT TIME ZONE 'Asia/Seoul' AS TIMESTAMP) - INTERVAL '7' DAY
      AND JSON_VALUE(action_payload, '$.type' NULL ON ERROR) = 'INSIGHT_OBSERVATION'
      AND JSON_VALUE(action_payload, '$.schemaVersion' RETURNING NUMBER NULL ON ERROR) = 1
      AND JSON_VALUE(action_payload, '$.agentRequestIssued' NULL ON ERROR) = 'true'
)
SELECT status, llm_provider, llm_model, prompt_version, metadata_source, usage_completeness,
       configured_model, expected_prompt, cache_outcome, current_count, history_count,
       COUNT(*) AS request_rows,
       SUM(input_tokens) AS known_input_tokens, SUM(output_tokens) AS known_output_tokens,
       SUM(cost_usd) AS known_cost_usd,
       SUM(CASE WHEN input_tokens IS NULL OR output_tokens IS NULL OR cost_usd IS NULL
                THEN 1 ELSE 0 END) AS unknown_usage_rows
FROM issued
GROUP BY status, llm_provider, llm_model, prompt_version, metadata_source, usage_completeness,
         configured_model, expected_prompt, cache_outcome, current_count, history_count
ORDER BY status, llm_provider, llm_model, prompt_version, history_count, current_count;
