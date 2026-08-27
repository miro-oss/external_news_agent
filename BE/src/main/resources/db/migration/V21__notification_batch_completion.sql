ALTER TABLE notification_delivery_batches ADD completed_at TIMESTAMP;

UPDATE notification_delivery_batches
SET completed_at = requested_at
WHERE completed_at IS NULL;
