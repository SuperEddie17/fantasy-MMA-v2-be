-- Captains, dividend payouts, daily net worth snapshots and leaderboards. Money columns are whole units.

-- One captain per portfolio and event; locked (by CaptainLockJob) once the weigh-in time has passed.
CREATE TABLE captain_pick (
    portfolio_id  BIGINT      NOT NULL REFERENCES portfolio (id),
    event_id      BIGINT      NOT NULL REFERENCES event (id),
    fighter_id    BIGINT      NOT NULL REFERENCES fighter (id),
    picked_at     TIMESTAMPTZ NOT NULL,
    locked_at     TIMESTAMPTZ,
    PRIMARY KEY (portfolio_id, event_id)
);

-- One row per lot eligible for a bout, also when it pays nothing (loss, draw), so a corrected result can
-- be recalculated at the same price. Current rows have reversed_at NULL; a corrected result reverses them
-- (ledger REVERSAL) and writes a row for the new result_version.
CREATE TABLE dividend_payout (
    id               BIGSERIAL    PRIMARY KEY,
    portfolio_id     BIGINT       NOT NULL REFERENCES portfolio (id),
    bout_id          BIGINT       NOT NULL REFERENCES bout (id),
    fighter_id       BIGINT       NOT NULL REFERENCES fighter (id),
    lot_id           BIGINT       NOT NULL REFERENCES position_lot (id),
    kind             TEXT         NOT NULL CHECK (kind IN ('LONG', 'SHORT')),
    -- price of the fighter just before the result, the basis of the yield formula
    price            BIGINT       NOT NULL CHECK (price > 0),
    -- price x yield x base x odds / norm (longs); settlement of the short (shorts)
    base             BIGINT       NOT NULL,
    odds_used        DOUBLE PRECISION,
    bonuses          BIGINT       NOT NULL DEFAULT 0,
    captain          BOOLEAN      NOT NULL DEFAULT FALSE,
    amount           BIGINT       NOT NULL,
    ledger_entry_id  BIGINT       REFERENCES ledger_entry (id),
    result_version   INTEGER      NOT NULL,
    reversed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (lot_id, bout_id, result_version)
);

CREATE INDEX dividend_payout_portfolio_idx ON dividend_payout (portfolio_id, id);
CREATE INDEX dividend_payout_bout_idx ON dividend_payout (bout_id) WHERE reversed_at IS NULL;

CREATE TABLE net_worth_snapshot (
    portfolio_id  BIGINT NOT NULL REFERENCES portfolio (id),
    day           DATE   NOT NULL,
    cash          BIGINT NOT NULL,
    long_value    BIGINT NOT NULL,
    short_value   BIGINT NOT NULL,
    net_worth     BIGINT NOT NULL,
    PRIMARY KEY (portfolio_id, day)
);

-- period: SEASON or MONTH-yyyy-MM. rank: 1 = best, equal values share a rank. change: net worth minus the
-- start of the period (the starting budget for SEASON). closed rows are final and never rebuilt.
CREATE TABLE leaderboard_entry (
    season_id     BIGINT      NOT NULL REFERENCES season (id),
    period        TEXT        NOT NULL,
    portfolio_id  BIGINT      NOT NULL REFERENCES portfolio (id),
    rank          INTEGER     NOT NULL CHECK (rank >= 1),
    net_worth     BIGINT      NOT NULL,
    change        BIGINT      NOT NULL,
    closed        BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (season_id, period, portfolio_id)
);

CREATE INDEX leaderboard_rank_idx ON leaderboard_entry (season_id, period, rank, portfolio_id);
