-- 생성 후 topic이 수정된 오래된 제안이 최신 키워드를 덮지 않도록 입력 baseline을 보존한다.
ALTER TABLE news_topic_keyword_proposals ADD (
    baseline_required_keywords CLOB DEFAULT '[]' NOT NULL,
    baseline_optional_keywords CLOB DEFAULT '[]' NOT NULL,
    baseline_excluded_keywords CLOB DEFAULT '[]' NOT NULL,
    CONSTRAINT ck_keyword_proposal_base_req CHECK (baseline_required_keywords IS JSON),
    CONSTRAINT ck_keyword_proposal_base_opt CHECK (baseline_optional_keywords IS JSON),
    CONSTRAINT ck_keyword_proposal_base_exc CHECK (baseline_excluded_keywords IS JSON)
);
