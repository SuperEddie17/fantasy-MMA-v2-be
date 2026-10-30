-- Operational state of each data source (plan.md F5): last success, last error, remaining API credits.
CREATE TABLE source_status (
    source          TEXT        PRIMARY KEY,
    last_success_at TIMESTAMPTZ,
    last_error      TEXT,
    last_error_at   TIMESTAMPTZ,
    credits_left    INTEGER CHECK (credits_left >= 0),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
