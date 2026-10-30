-- Weekly inactivity decays already applied to a listing (weeks without a fight beyond `inactiveWeeks`),
-- so the daily InactivityJob can run any number of times and still decay once per elapsed week.
ALTER TABLE market_listing ADD COLUMN inactivity_ticks INT NOT NULL DEFAULT 0 CHECK (inactivity_ticks >= 0);
