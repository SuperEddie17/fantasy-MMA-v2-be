-- Rules v2 defaults (same values as com.fightexchange.rules.RuleSet.V2; DbSetupTest checks they match).
-- Money in whole units (1 M = 1 000 000), rates as fractions.
INSERT INTO rule_set (version, valid_from, params) VALUES (2, '2026-01-01T00:00:00Z', '{
  "budget": 100000000,
  "eloStart": 1500.0, "eloK": 40.0, "eloFinishMult": 1.25,
  "priceAt1500": 6000000, "priceScale": 200.0, "priceMin": 500000,
  "annNormal": 0.02, "annMain": 0.05, "annTitle": 0.10,
  "annStrongerOpp": [{"above": 0.0, "bump": 0.01}, {"above": 100.0, "bump": 0.02}, {"above": 200.0, "bump": 0.03}],
  "cancelDropMin": 0.05, "cancelDropMax": 0.10,
  "oddsDrift": 0.05,
  "inactiveWeeks": 26.0, "decay": 0.01, "delistWeeks": 78.0,
  "demandCap": 0.30,
  "baseDec": 1.0, "baseFin": 1.5, "oddsCap": 3.5, "defaultOdds": 2.0,
  "yieldScale": 0.25, "yieldNorm": 1.2,
  "bonusR1": 500000, "bonusNight": 1000000, "bonusTitle": 3000000, "weightMiss": -1000000,
  "captainMult": 2.0, "shortUnit": 1000000,
  "fee": 0.01, "freeBuysPerWeek": 2, "extraBuyFee": 0.03,
  "minRoster": 8, "maxRoster": 15, "maxShorts": 3, "minNonUfc": 2
}'::jsonb);
