-- Players, seasons, portfolios and the trading book. Money columns are whole units (1 M = 1 000 000).
-- Cash is the sum of ledger_entry.amount; portfolio.cash_cached is kept in step in the same transaction
-- and checked nightly.

CREATE TABLE app_user (
    id             BIGSERIAL   PRIMARY KEY,
    email          TEXT        NOT NULL,
    password_hash  TEXT,
    display_name   TEXT        NOT NULL,
    role           TEXT        NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- e-mail is compared case-insensitively
CREATE UNIQUE INDEX app_user_email_idx ON app_user (lower(email));

CREATE TABLE season (
    id           BIGSERIAL   PRIMARY KEY,
    name         TEXT        NOT NULL,
    starts_at    TIMESTAMPTZ NOT NULL,
    ends_at      TIMESTAMPTZ NOT NULL,
    rule_set_id  BIGINT      NOT NULL REFERENCES rule_set (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at)
);

CREATE TABLE portfolio (
    id           BIGSERIAL   PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES app_user (id),
    season_id    BIGINT      NOT NULL REFERENCES season (id),
    -- null = the global market; leagues come in F6
    league_id    BIGINT,
    cash_cached  BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX portfolio_global_idx ON portfolio (user_id, season_id) WHERE league_id IS NULL;
CREATE UNIQUE INDEX portfolio_league_idx ON portfolio (user_id, season_id, league_id) WHERE league_id IS NOT NULL;

CREATE TABLE trade (
    id               BIGSERIAL   PRIMARY KEY,
    portfolio_id     BIGINT      NOT NULL REFERENCES portfolio (id),
    fighter_id       BIGINT      NOT NULL REFERENCES fighter (id),
    kind             TEXT        NOT NULL CHECK (kind IN ('BUY', 'SELL', 'SHORT_OPEN', 'SHORT_CLOSE', 'FORCED_SELL')),
    price            BIGINT      NOT NULL CHECK (price > 0),
    fee              BIGINT      NOT NULL DEFAULT 0 CHECK (fee >= 0),
    extra_fee        BIGINT      NOT NULL DEFAULT 0 CHECK (extra_fee >= 0),
    free_buy         BOOLEAN     NOT NULL DEFAULT FALSE,
    at               TIMESTAMPTZ NOT NULL,
    -- client supplied; a repeated request returns the stored trade
    idempotency_key  TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX trade_idempotency_idx ON trade (portfolio_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX trade_portfolio_idx ON trade (portfolio_id, id);
CREATE INDEX trade_fighter_at_idx ON trade (fighter_id, at);

-- One piece = one lot (FIFO and dividend eligibility work per piece).
CREATE TABLE position_lot (
    id            BIGSERIAL   PRIMARY KEY,
    portfolio_id  BIGINT      NOT NULL REFERENCES portfolio (id),
    fighter_id    BIGINT      NOT NULL REFERENCES fighter (id),
    side          TEXT        NOT NULL CHECK (side IN ('LONG', 'SHORT')),
    opened_at     TIMESTAMPTZ NOT NULL,
    open_price    BIGINT      NOT NULL CHECK (open_price > 0),
    open_trade_id BIGINT      REFERENCES trade (id),
    closed_at     TIMESTAMPTZ,
    close_price   BIGINT,
    close_trade_id BIGINT     REFERENCES trade (id),
    CHECK ((closed_at IS NULL) = (close_price IS NULL))
);

CREATE INDEX position_lot_portfolio_idx ON position_lot (portfolio_id, fighter_id) WHERE closed_at IS NULL;
CREATE INDEX position_lot_fighter_idx ON position_lot (fighter_id) WHERE closed_at IS NULL;
-- a portfolio holds a fighter at most once per side
CREATE UNIQUE INDEX position_lot_open_idx ON position_lot (portfolio_id, fighter_id, side) WHERE closed_at IS NULL;

CREATE TABLE ledger_entry (
    id            BIGSERIAL   PRIMARY KEY,
    portfolio_id  BIGINT      NOT NULL REFERENCES portfolio (id),
    at            TIMESTAMPTZ NOT NULL,
    kind          TEXT        NOT NULL CHECK (kind IN (
                      'START_BUDGET', 'TRADE', 'FEE', 'EXTRA_FEE', 'DIVIDEND', 'SHORT_SETTLE',
                      'DELIST_PAYOUT', 'REVERSAL')),
    amount        BIGINT      NOT NULL,
    trade_id      BIGINT      REFERENCES trade (id),
    bout_id       BIGINT      REFERENCES bout (id),
    reverses_id   BIGINT      REFERENCES ledger_entry (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entry_portfolio_idx ON ledger_entry (portfolio_id, id);
CREATE UNIQUE INDEX ledger_entry_reverses_idx ON ledger_entry (reverses_id) WHERE reverses_id IS NOT NULL;

-- Buys made in a game week (starts Monday 00:00 UTC), for the free-buys allowance.
CREATE TABLE weekly_buy_counter (
    portfolio_id  BIGINT NOT NULL REFERENCES portfolio (id),
    week_start    DATE   NOT NULL,
    buys          INTEGER NOT NULL DEFAULT 0 CHECK (buys >= 0),
    PRIMARY KEY (portfolio_id, week_start)
);
