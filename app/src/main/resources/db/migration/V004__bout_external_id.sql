-- Bouts are addressed by sources via an external id (scoped to the ingest source); cancelled bouts remember
-- which fighter withdrew.
ALTER TABLE bout
    ADD COLUMN source               TEXT,
    ADD COLUMN external_id          TEXT,
    ADD COLUMN withdrawn_fighter_id BIGINT REFERENCES fighter (id);

CREATE UNIQUE INDEX bout_source_external_idx ON bout (source, external_id) WHERE external_id IS NOT NULL;
