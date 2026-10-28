ALTER TABLE notification_recipients ADD (
    auto_insight_audience VARCHAR2(30) DEFAULT 'NONE' NOT NULL
        CHECK (auto_insight_audience IN ('NONE', 'CHIP_MAKER', 'EQUIPMENT_MAKER', 'MARKET_INVESTOR', 'IT_INFRA'))
);
