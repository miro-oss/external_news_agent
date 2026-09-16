-- Nullable means the original picker selection predates this migration. Keep its
-- exact recipient/channel/address pairs; do not reconstruct a Cartesian product.
ALTER TABLE run_delivery_settings ADD (
    group_ids CLOB CHECK (group_ids IS JSON),
    recipient_ids CLOB CHECK (recipient_ids IS JSON),
    channel_ids CLOB CHECK (channel_ids IS JSON)
);
