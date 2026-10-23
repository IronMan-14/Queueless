ALTER TABLE appointments ADD COLUMN disruption_protected BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE queue_events MODIFY actor_id BIGINT NULL;
ALTER TABLE appointment_events MODIFY actor_id BIGINT NULL;
