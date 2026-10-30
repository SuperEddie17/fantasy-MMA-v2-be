-- Market: one listing per fighter, an append-only log of price changes with reasons, and daily OHLC for charts.
-- Prices are Money (BIGINT, 1 M = 1 000 000); factors are plain numbers (1.0 = no effect).

CREATE TABLE market_listing (
    fighter_id        BIGINT      PRIMARY KEY REFERENCES fighter (id),
    status            TEXT        NOT NULL DEFAULT 'IPO' CHECK (status IN ('IPO', 'ACTIVE', 'DELISTED')),
    base_value        BIGINT      NOT NULL CHECK (base_value > 0),
    event_multiplier  NUMERIC(12, 6) NOT NULL DEFAULT 1 CHECK (event_multiplier > 0),
    demand_factor     NUMERIC(12, 6) NOT NULL DEFAULT 1 CHECK (demand_factor > 0),
    price             BIGINT      NOT NULL CHECK (price > 0),
    listed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    delisted_at       TIMESTAMPTZ,
    last_fight_at     TIMESTAMPTZ,
    CHECK ((status = 'DELISTED') = (delisted_at IS NOT NULL))
);

CREATE INDEX market_listing_status_idx ON market_listing (status);

CREATE TABLE price_event (
    id               BIGSERIAL   PRIMARY KEY,
    fighter_id       BIGINT      NOT NULL REFERENCES fighter (id),
    at               TIMESTAMPTZ NOT NULL,
    kind             TEXT        NOT NULL CHECK (kind IN (
                         'ELO', 'ANNOUNCED', 'OPPONENT_BONUS', 'CANCELLED', 'RANKING',
                         'ODDS_DRIFT', 'INACTIVITY', 'DEMAND', 'RESULT_RESET', 'DELIST')),
    factor           NUMERIC(12, 6),
    price_before     BIGINT      NOT NULL,
    price_after      BIGINT      NOT NULL,
    bout_id          BIGINT      REFERENCES bout (id),
    ingest_event_id  BIGINT      REFERENCES ingest_event (id),
    note             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX price_event_fighter_idx ON price_event (fighter_id, at, id);
CREATE INDEX price_event_ingest_idx ON price_event (ingest_event_id) WHERE ingest_event_id IS NOT NULL;

CREATE TABLE price_daily (
    fighter_id  BIGINT NOT NULL REFERENCES fighter (id),
    day         DATE   NOT NULL,
    open        BIGINT NOT NULL,
    close       BIGINT NOT NULL,
    low         BIGINT NOT NULL,
    high        BIGINT NOT NULL,
    PRIMARY KEY (fighter_id, day),
    CHECK (low <= open AND low <= close AND high >= open AND high >= close)
);
