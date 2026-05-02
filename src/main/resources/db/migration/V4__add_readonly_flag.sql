-- Add read_only flag to connector_configs
-- When true, the system blocks all write operations against that data source

ALTER TABLE connector_configs
    ADD COLUMN IF NOT EXISTS read_only BOOLEAN NOT NULL DEFAULT FALSE;
