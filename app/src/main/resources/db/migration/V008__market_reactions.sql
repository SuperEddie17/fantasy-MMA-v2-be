-- Rules v2 gain the ranking parameters (same values as RuleSet.V2; DbSetupTest checks they match).
UPDATE rule_set
SET params = params || '{"rankingStep": 0.005, "rankingCap": 0.05, "rankingUnranked": 16}'::jsonb
WHERE version = 2;

-- Fight-week odds drift currently applied to a fighter's event multiplier because of a bout, so a newer odds
-- snapshot moves only the difference and a cancelled or completed bout can take the drift back.
CREATE TABLE bout_price_drift (
    bout_id     BIGINT NOT NULL REFERENCES bout (id) ON DELETE CASCADE,
    fighter_id  BIGINT NOT NULL REFERENCES fighter (id),
    factor      NUMERIC(12, 6) NOT NULL CHECK (factor > 0),
    PRIMARY KEY (bout_id, fighter_id)
);

CREATE INDEX bout_price_drift_fighter_idx ON bout_price_drift (fighter_id);
