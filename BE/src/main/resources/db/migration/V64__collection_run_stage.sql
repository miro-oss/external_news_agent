-- Existing RUNNING rows keep NULL because their actual worker stage is unknown.
ALTER TABLE news_collection_runs ADD stage VARCHAR2(30);
ALTER TABLE news_collection_runs ADD CONSTRAINT ck_run_stage CHECK (
    stage IS NULL OR (
        status = 'RUNNING' AND stage IN (
            'COLLECTING', 'CLUSTERING', 'ANALYZING', 'INVESTIGATING',
            'GENERATING_REPORT', 'FINALIZING'
        )
    )
);
