-- Versioned game rules. Code always reads the version valid at the time of the action.
CREATE TABLE rule_set (
    id          BIGSERIAL PRIMARY KEY,
    version     INTEGER     NOT NULL UNIQUE,
    valid_from  TIMESTAMPTZ NOT NULL,
    params      JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX rule_set_valid_from_idx ON rule_set (valid_from);
