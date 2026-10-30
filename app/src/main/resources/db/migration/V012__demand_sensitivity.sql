-- Rules v2 gain the demand sensitivity (same value as RuleSet.V2; DbSetupTest checks they match).
UPDATE rule_set
SET params = params || '{"demandSensitivity": 0.5}'::jsonb
WHERE version = 2;
