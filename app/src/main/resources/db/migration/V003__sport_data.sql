-- Sport data: organizations, fighters, events, bouts, results, odds, rankings, Elo, ingest and review queue.
-- Enumerations are stored as text with CHECK constraints (easier to evolve than PG enums).

CREATE TABLE organization (
    id           BIGSERIAL PRIMARY KEY,
    code         TEXT    NOT NULL UNIQUE,
    name         TEXT    NOT NULL,
    manual_data  BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO organization (code, name, manual_data) VALUES
    ('UFC', 'Ultimate Fighting Championship', FALSE),
    ('PFL', 'Professional Fighters League', FALSE),
    ('ONE', 'ONE Championship', TRUE),
    ('OKT', 'Oktagon MMA', FALSE),
    ('KSW', 'Konfrontacja Sztuk Walki', TRUE);

CREATE TABLE fighter (
    id               BIGSERIAL PRIMARY KEY,
    name             TEXT        NOT NULL,
    normalized_name  TEXT        NOT NULL,
    nickname         TEXT,
    sex              TEXT        NOT NULL CHECK (sex IN ('M', 'F')),
    division         TEXT,
    organization_id  BIGINT      NOT NULL REFERENCES organization (id),
    country          TEXT,
    merged_into_id   BIGINT      REFERENCES fighter (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX fighter_normalized_name_idx ON fighter (normalized_name);
CREATE INDEX fighter_org_division_idx ON fighter (organization_id, division);

CREATE TABLE fighter_external_id (
    fighter_id   BIGINT NOT NULL REFERENCES fighter (id),
    source       TEXT   NOT NULL,
    external_id  TEXT   NOT NULL,
    PRIMARY KEY (fighter_id, source),
    UNIQUE (source, external_id)
);

CREATE TABLE event (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT      NOT NULL REFERENCES organization (id),
    name             TEXT        NOT NULL,
    starts_at        TIMESTAMPTZ NOT NULL,
    local_date       DATE,
    weigh_in_at      TIMESTAMPTZ,
    status           TEXT        NOT NULL DEFAULT 'SCHEDULED'
                         CHECK (status IN ('SCHEDULED', 'LIVE', 'COMPLETED', 'CANCELLED')),
    source           TEXT        NOT NULL,
    external_id      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX event_starts_at_idx ON event (starts_at);
CREATE INDEX event_org_idx ON event (organization_id, starts_at);
CREATE UNIQUE INDEX event_source_external_idx ON event (source, external_id) WHERE external_id IS NOT NULL;

CREATE TABLE bout (
    id                    BIGSERIAL PRIMARY KEY,
    event_id              BIGINT      NOT NULL REFERENCES event (id),
    fighter_a_id          BIGINT      NOT NULL REFERENCES fighter (id),
    fighter_b_id          BIGINT      NOT NULL REFERENCES fighter (id),
    bout_type             TEXT        NOT NULL DEFAULT 'NORMAL' CHECK (bout_type IN ('NORMAL', 'MAIN', 'TITLE')),
    status                TEXT        NOT NULL DEFAULT 'ANNOUNCED'
                              CHECK (status IN ('ANNOUNCED', 'CONFIRMED', 'NEEDS_REVIEW', 'CANCELLED', 'COMPLETED')),
    announced_at_source   TIMESTAMPTZ,
    announced_at_seen     TIMESTAMPTZ,
    announced_at_admin    TIMESTAMPTZ,
    -- earliest known announcement time, maintained on write
    announced_at          TIMESTAMPTZ GENERATED ALWAYS AS (
                              LEAST(announced_at_source, announced_at_seen, announced_at_admin)
                          ) STORED,
    cancel_reason         TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (fighter_a_id <> fighter_b_id)
);

CREATE INDEX bout_event_idx ON bout (event_id);
CREATE INDEX bout_fighter_a_idx ON bout (fighter_a_id);
CREATE INDEX bout_fighter_b_idx ON bout (fighter_b_id);

CREATE TABLE bout_result (
    id         BIGSERIAL PRIMARY KEY,
    bout_id    BIGINT      NOT NULL REFERENCES bout (id),
    version    INTEGER     NOT NULL,
    outcome    TEXT        NOT NULL CHECK (outcome IN ('WIN', 'DRAW', 'NO_CONTEST')),
    winner_id  BIGINT      REFERENCES fighter (id),
    method     TEXT        NOT NULL
                   CHECK (method IN ('KO_TKO', 'SUB', 'DEC_U', 'DEC_S', 'DEC_M', 'DQ', 'NC', 'DRAW')),
    round      INTEGER,
    time       TEXT,
    title_won  BOOLEAN     NOT NULL DEFAULT FALSE,
    is_current BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (bout_id, version),
    CHECK ((outcome = 'WIN') = (winner_id IS NOT NULL))
);

CREATE UNIQUE INDEX bout_result_current_idx ON bout_result (bout_id) WHERE is_current;

CREATE TABLE bout_bonus (
    bout_id     BIGINT NOT NULL REFERENCES bout (id),
    fighter_id  BIGINT NOT NULL REFERENCES fighter (id),
    type        TEXT   NOT NULL CHECK (type IN ('FIGHT_OF_NIGHT', 'PERFORMANCE')),
    PRIMARY KEY (bout_id, fighter_id, type)
);

CREATE TABLE weight_miss (
    bout_id     BIGINT NOT NULL REFERENCES bout (id),
    fighter_id  BIGINT NOT NULL REFERENCES fighter (id),
    PRIMARY KEY (bout_id, fighter_id)
);

CREATE TABLE odds_snapshot (
    id          BIGSERIAL PRIMARY KEY,
    bout_id     BIGINT           NOT NULL REFERENCES bout (id),
    source      TEXT             NOT NULL,
    taken_at    TIMESTAMPTZ      NOT NULL,
    odds_a      DOUBLE PRECISION NOT NULL CHECK (odds_a > 1),
    odds_b      DOUBLE PRECISION NOT NULL CHECK (odds_b > 1),
    is_closing  BOOLEAN          NOT NULL DEFAULT FALSE
);

CREATE INDEX odds_snapshot_bout_idx ON odds_snapshot (bout_id, taken_at);

CREATE TABLE ranking_snapshot (
    division    TEXT        NOT NULL,
    taken_at    TIMESTAMPTZ NOT NULL,
    fighter_id  BIGINT      NOT NULL REFERENCES fighter (id),
    rank        INTEGER     NOT NULL,
    PRIMARY KEY (division, taken_at, fighter_id)
);

CREATE TABLE elo_rating (
    id             BIGSERIAL PRIMARY KEY,
    fighter_id     BIGINT           NOT NULL REFERENCES fighter (id),
    bout_id        BIGINT           NOT NULL REFERENCES bout (id),
    rating_before  DOUBLE PRECISION NOT NULL,
    rating_after   DOUBLE PRECISION NOT NULL,
    rated_at       TIMESTAMPTZ      NOT NULL,
    UNIQUE (fighter_id, bout_id)
);

CREATE INDEX elo_rating_fighter_idx ON elo_rating (fighter_id, rated_at);

CREATE TABLE ingest_event (
    id           BIGSERIAL PRIMARY KEY,
    source       TEXT        NOT NULL,
    external_key TEXT        NOT NULL,
    type         TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'NEW'
                     CHECK (status IN ('NEW', 'APPLIED', 'NEEDS_REVIEW', 'REJECTED', 'DUPLICATE')),
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_at   TIMESTAMPTZ
);

-- Duplicates are stored too (status DUPLICATE), so uniqueness only covers the original.
CREATE UNIQUE INDEX ingest_event_original_idx ON ingest_event (source, external_key) WHERE status <> 'DUPLICATE';
CREATE INDEX ingest_event_status_idx ON ingest_event (status, id);

CREATE TABLE review_item (
    id               BIGSERIAL PRIMARY KEY,
    ingest_event_id  BIGINT      NOT NULL REFERENCES ingest_event (id),
    reason           TEXT        NOT NULL
                         CHECK (reason IN ('FIGHTER_MATCH', 'CANCELLATION', 'RESULT_CONFIRM', 'UNKNOWN_BOUT')),
    status           TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CONFIRMED', 'REJECTED', 'ASSIGNED')),
    resolved_by      TEXT,
    resolved_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX review_item_status_idx ON review_item (status);
