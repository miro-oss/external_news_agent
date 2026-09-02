-- P2-2. 일반 위험도 3단계를 회사 맥락 4축 민감도로 교체한다.
-- 기존 행은 표시 연속성을 위해 총점만 백필하고, 근거를 만들 수 없는 축은 unavailable(NULL)로 둔다.

ALTER TABLE news_findings ADD (
    sensitivity_score            NUMBER(6, 2),
    customer_move_score          NUMBER(1),
    customer_move_evidence       CLOB DEFAULT '[]' NOT NULL,
    deal_signal_score            NUMBER(1),
    deal_signal_evidence         CLOB DEFAULT '[]' NOT NULL,
    competitor_threat_score      NUMBER(1),
    competitor_threat_evidence   CLOB DEFAULT '[]' NOT NULL,
    industry_shift_score         NUMBER(1),
    industry_shift_evidence      CLOB DEFAULT '[]' NOT NULL
);

UPDATE news_findings
SET sensitivity_score = CASE risk_level
    WHEN 'HIGH' THEN 83.33
    WHEN 'MEDIUM' THEN 50.00
    ELSE 16.67
END;

ALTER TABLE news_findings MODIFY sensitivity_score NOT NULL;
ALTER TABLE news_findings DROP CONSTRAINT ck_finding_risk_level;
DROP INDEX ix_finding_filters;
ALTER TABLE news_findings DROP COLUMN risk_level;

ALTER TABLE news_findings ADD CONSTRAINT ck_finding_sensitivity_score
    CHECK (sensitivity_score BETWEEN 0 AND 100);
ALTER TABLE news_findings ADD CONSTRAINT ck_finding_sensitivity_axes CHECK (
    (customer_move_score IS NULL OR (customer_move_score BETWEEN 0 AND 3
        AND customer_move_score = TRUNC(customer_move_score)))
    AND (deal_signal_score IS NULL OR (deal_signal_score BETWEEN 0 AND 3
        AND deal_signal_score = TRUNC(deal_signal_score)))
    AND (competitor_threat_score IS NULL OR (competitor_threat_score BETWEEN 0 AND 3
        AND competitor_threat_score = TRUNC(competitor_threat_score)))
    AND (industry_shift_score IS NULL OR (industry_shift_score BETWEEN 0 AND 3
        AND industry_shift_score = TRUNC(industry_shift_score)))
);
ALTER TABLE news_findings ADD CONSTRAINT ck_finding_customer_move_evidence_json
    CHECK (customer_move_evidence IS JSON);
ALTER TABLE news_findings ADD CONSTRAINT ck_finding_deal_signal_evidence_json
    CHECK (deal_signal_evidence IS JSON);
ALTER TABLE news_findings ADD CONSTRAINT ck_finding_competitor_threat_evidence_json
    CHECK (competitor_threat_evidence IS JSON);
ALTER TABLE news_findings ADD CONSTRAINT ck_finding_industry_shift_evidence_json
    CHECK (industry_shift_evidence IS JSON);

CREATE INDEX ix_finding_filters ON news_findings (relevance, sensitivity_score, category);
