-- Announcement bump currently applied to a fighter's event multiplier because of a bout.
-- Kept so a changed or cancelled bout can take back exactly what it added (base = bout type, opp = stronger opponent).

CREATE TABLE bout_price_bump (
    bout_id     BIGINT NOT NULL REFERENCES bout (id),
    fighter_id  BIGINT NOT NULL REFERENCES fighter (id),
    base_bump   NUMERIC(8, 6) NOT NULL CHECK (base_bump >= 0),
    opp_bump    NUMERIC(8, 6) NOT NULL CHECK (opp_bump >= 0),
    PRIMARY KEY (bout_id, fighter_id)
);

CREATE INDEX bout_price_bump_fighter_idx ON bout_price_bump (fighter_id);
