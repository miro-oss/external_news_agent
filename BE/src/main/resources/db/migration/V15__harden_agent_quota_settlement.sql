-- V14는 이미 적용된 환경이 있으므로 정산 불변식 강화는 후속 migration으로 추가한다.
ALTER TABLE agent_quota_reservations DROP CONSTRAINT ck_agent_quota_units;

ALTER TABLE agent_quota_reservations ADD CONSTRAINT ck_agent_quota_units
    CHECK (
        reserved_units > 0
        AND (consumed_units IS NULL
            OR (consumed_units >= 0 AND consumed_units <= reserved_units))
    );

ALTER TABLE agent_runs ADD (timeout_phase VARCHAR2(10));

ALTER TABLE agent_runs ADD CONSTRAINT ck_agent_run_timeout_phase
    CHECK (timeout_phase IN ('CONNECT', 'READ'));
