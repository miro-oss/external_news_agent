# Local Fullstack Plan

## Scope

This plan covers the first PoC task: collect full-text external news articles, extract semiconductor-industry signals, and generate decision-support reports. The crawler/agent internals stay out of the first implementation pass; the Spring API, React screens, database schema, and CI should be shaped so the agent can be attached later without reworking the product boundary.

The local-only target is:

- Frontend: React + Vite under `FE`
- Backend: Spring Boot under `BE`
- Database: Oracle AI Database Free 26ai container for relational data plus vector search
- Runtime: local developer machine, no production deployment path

## Source Inputs

The proposal deck defines task 1 as "external news full-text crawling for semiconductor-industry extraction and report generation." The supporting workbook frames the workflow as target definition, strategy setup, automatic execution, result analysis, and delivery. The public-monitoring documents are useful mainly as a reference pattern for incremental collection, document hashing, report history, and delivery logs.

Relevant source categories from the workbook:

- Domestic macro and policy RSS: Hankyung, Maeil Business, Google News business/world feeds
- Domestic semiconductor and IT RSS: ETNews, ZDNet Korea, Hankyung IT, Google News semiconductor search
- Global technical and market sources: EE Times, DIGITIMES Asia, Semiconductor Engineering, SemiWiki, TrendForce, WSTS, TechInsights
- Global business and policy pages: Reuters Technology, CNBC Technology, Nikkei Asia Semiconductors

## Oracle Direction

Use Oracle AI Database Free 26ai for the local vector database path. Do not build the vector feature on legacy Oracle XE naming or an older XE install unless its version is confirmed to support the `VECTOR` type.

Reasons:

- Oracle's current free local database line is Oracle AI Database Free 26ai.
- AI Vector Search is included in Oracle AI Database Free.
- Oracle supports columns such as `VECTOR(1536, FLOAT32)` and vector indexes for approximate similarity search.

Suggested local container baseline:

```bash
export ORACLE_PWD='local-dev-password'
docker volume create external-news-oradata
docker pull container-registry.oracle.com/database/free:latest-lite
docker image inspect \
  --format '{{index .RepoDigests 0}}' \
  container-registry.oracle.com/database/free:latest-lite
docker run -d \
  --name external-news-oracle \
  -p 1521:1521 \
  -e ORACLE_PWD="$ORACLE_PWD" \
  -v external-news-oradata:/opt/oracle/oradata \
  container-registry.oracle.com/database/free@sha256:<verified-26ai-lite-digest>
```

Oracle's local startup guide documents `latest-lite`; for repeatable local setup, resolve it once with `docker image inspect`, confirm it is a 26ai image, then run the image by digest. Record the digest in local setup notes when migrations are tested.

Wait for Oracle before starting Spring:

```bash
until [ "$(docker inspect -f '{{.State.Health.Status}}' external-news-oracle 2>/dev/null)" = "healthy" ] \
  || docker logs external-news-oracle 2>&1 | grep -q "DATABASE IS READY TO USE!"; do
  sleep 5
done
docker exec external-news-oracle sqlplus -L system/"$ORACLE_PWD"@FREEPDB1 \
  <<< "SELECT sys_context('USERENV', 'CON_NAME') FROM dual; exit;"
```

Use service `FREEPDB1` for the Spring datasource unless the installed database exposes a different pluggable database. Keep credentials in local environment variables or an ignored `.env`, not in GitHub secrets, because deployment is not in scope. Start Spring only after the listener accepts connections and the `FREEPDB1` query succeeds.

## Backend Plan

Use a layered Spring structure:

- `controller`: REST endpoints for source settings, runs, articles, reports, and delivery settings
- `service`: workflow orchestration, report assembly, search, and status transitions
- `repository`: JPA/JdbcClient access to Oracle tables
- `integration`: RSS/news API/agent adapter boundaries
- `config`: datasource, scheduling, CORS, and external API settings

Initial API surface:

- `GET /api/news-sources`
- `POST /api/news-sources`
- `PATCH /api/news-sources/{id}`
- `POST /api/news-runs/manual`
- `GET /api/news-runs`
- `GET /api/news-runs/{id}`
- `GET /api/articles`
- `GET /api/reports/latest`
- `GET /api/search?query=...`

Source DTO contract:

```json
{
  "id": 1,
  "name": "Google News Semiconductor",
  "sourceType": "RSS",
  "url": "https://news.google.com/rss/search?q=반도체&hl=ko&gl=KR",
  "country": "KR",
  "language": "ko",
  "enabled": true,
  "crawlPolicy": {
    "robotsMode": "respect",
    "maxArticlesPerRun": 30,
    "fullTextAllowed": true
  },
  "keywordPolicy": {
    "include": ["반도체", "HBM", "EUV"],
    "exclude": ["광고"]
  },
  "robotsPolicyStatus": {
    "status": "allowed",
    "checkedAt": "2026-08-06T09:00:00+09:00"
  }
}
```

`keywordPolicy` is persisted as part of `news_sources`. `robotsPolicyStatus` is computed by the backend from the source URL and stored as the latest check result so the console can display it without recalculating on every render.

Manual run behavior:

- `POST /api/news-runs/manual` accepts an optional idempotency key: `{"idempotencyKey":"2026-08-06-manual-001","forceRefresh":false}`.
- If a run is already active with the same key, return `200 OK` with the existing run.
- If another active run exists for overlapping enabled sources, return `409 Conflict` with an error code such as `NEWS_RUN_ALREADY_ACTIVE`.
- Run list and article list endpoints use bounded pagination: `page` starts at `0`, `size` defaults to `20`, and `size` must not exceed `100`.
- Search endpoints cap `limit` at `20` and should include the embedding model used for the query.
- Validation errors return `400`, missing rows return `404`, and unexpected agent/crawler failures are stored as run warnings while the API returns the run status.

Delivery-settings endpoints are not part of the first local UI. Keep delivery tables in the model for later Telegram/email work, but do not claim controller support until those screens are implemented.

Do not put Playwright or crawling logic directly into controllers. Model it as an adapter contract so a later agent process can call back into the same persistence and report APIs.

## Data Model Draft

Core relational tables:

- `news_sources`: `id` primary key, source name, source type, URL, country, language, enabled flag, crawl policy, keyword policy, latest robots policy status. Add a unique key on `(source_type, url)`.
- `news_collection_runs`: `id` primary key, execution status, started/finished timestamps, scanned count, changed count, warnings, report id. Add indexes on `(status, started_at)` and `(started_at)`.
- `news_articles`: `id` primary key, `source_id` foreign key to `news_sources`, canonical URL, title, publisher, published time, fetched time, body text, body hash, relevance, risk level. Deduplicate with a unique key on `(source_id, canonical_url)` and store `body_hash` for change detection.
- `news_article_chunks`: `id` primary key, `article_id` foreign key to `news_articles`, chunk order, text, token estimate, embedding model, embedding vector. Add a unique key on `(article_id, chunk_no, embedding_model)`.
- `news_reports`: `id` primary key, `run_id` unique foreign key to `news_collection_runs`, title, markdown body, generated time, model name.
- `news_findings`: `id` primary key, `run_id` foreign key to `news_collection_runs`, `article_id` foreign key to `news_articles`, change type, summary, key points, semiconductor category. Add a unique key on `(run_id, article_id)`.
- `delivery_groups`: `id` primary key, recipient group and perspective such as executive, supply-chain, or technology.
- `delivery_logs`: `id` primary key, `run_id` foreign key to `news_collection_runs`, channel, target, status, message id, error text, sent time. Add an index on `(run_id, status)`.

Relationship rules:

- One source has many articles.
- One run can produce many findings and at most one report.
- One article has many chunks, but a chunk is unique per `(article_id, chunk_no, embedding_model)`.
- Repeated runs reuse existing `news_articles` rows and create new `news_findings` rows only when the run observes the article.
- `embedding` may be null before embedding generation succeeds. Semantic search must filter `embedding IS NOT NULL`.

Vector table example:

```sql
CREATE TABLE news_article_chunks (
  id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  article_id NUMBER NOT NULL
    REFERENCES news_articles(id),
  chunk_no NUMBER NOT NULL,
  chunk_text CLOB NOT NULL,
  embedding_model VARCHAR2(100) NOT NULL,
  embedding VECTOR(1536, FLOAT32),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT uq_news_article_chunk
    UNIQUE (article_id, chunk_no, embedding_model)
);

CREATE VECTOR INDEX news_article_chunks_hnsw_idx
ON news_article_chunks (embedding)
ORGANIZATION INMEMORY NEIGHBOR GRAPH
DISTANCE COSINE
WITH TARGET ACCURACY 90;
```

Set the vector dimension from the actual embedding model before migration. Keep one embedding dimension/model family per indexed table. Reuse `embedding_model` as the model identity and isolate incompatible embeddings by using separate model-specific chunk tables, separate indexes, or strict query filters before creating or using a vector index. Do not compare vectors generated by different models.

## Frontend Plan

Build the first screen as an operating console, not a landing page:

- Source settings: enabled sources, RSS/API/crawl type, keyword policy, robots policy status
- Manual run panel: execute now, last status, warning count
- Latest report: markdown report body, grouped by risk and semiconductor category
- Article table: title, publisher, published time, change type, relevance, risk, source URL
- Semantic search: query input backed by Oracle vector similarity through the Spring API

Keep the UI dense and operational. The core user is checking source health, recent signal quality, and report output, not reading marketing copy.

## Implementation Order

1. Normalize CI so `FE` and `BE` are validated independently.
2. Add Spring dependencies for Oracle JDBC, validation, scheduling, persistence, and migrations.
3. Add local datasource profile and ignored local env convention.
4. Add schema migrations for source/run/article/report tables.
5. Implement CRUD and read-only dashboard APIs with sample seed data.
6. Build React operating console against those APIs.
7. Add vector chunk table and semantic search API.
8. Attach crawler/agent adapter behind the manual run endpoint.
9. Add scheduled execution only after manual runs are stable.

## Local Verification Target

Before agent integration, local verification should pass:

- Record tool versions: `node --version`, `pnpm --version`, and `java -version`; compare them with CI's Node 24, pnpm 11, and JDK 21 setup.
- `cd FE && pnpm install --frozen-lockfile && pnpm lint && pnpm build`
- `cd BE && ./gradlew test`
- Oracle container accepts a connection to `FREEPDB1`
- Spring can create/read source rows and report rows
- React can render latest report and article list from the Spring API
- Source settings API can create/update `crawlPolicy` and `keywordPolicy`, and the console displays latest `robotsPolicyStatus`
- Manual run API rejects overlapping runs with `409 Conflict` or returns the existing run for the same idempotency key
- Embedding generation can create at least one non-null `VECTOR(1536, FLOAT32)` value for an article chunk
- Semantic search returns bounded, model-compatible results and ignores chunks without embeddings
- Vector index creation succeeds after model/dimension isolation rules are applied

## CI Notes

The previous CI file was copied from another project. It assumed `gradlew` existed at the repository root, called `spotlessCheck` without a Spotless plugin, and carried unrelated production secret names. The corrected workflow should stay local-project focused: frontend install/lint/build and backend Gradle tests only.

## References

- Oracle AI Database Free: https://www.oracle.com/database/free/
- Oracle local container startup: https://docs.oracle.com/en/database/oracle/agent-memory/26.4/agmea/run-locally.html
- Oracle vector data type: https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/Data-Types.html
- Oracle vector index syntax: https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/create-vector-index.html
