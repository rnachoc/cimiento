-- A tender may be registered before its contract term is known (an agent did not find it).
-- The CONTRACT_TERM rule then warns and forces human review instead of blocking everything.
ALTER TABLE tender ALTER COLUMN contract_term_months DROP NOT NULL;
ALTER TABLE schedule ALTER COLUMN contract_deadline DROP NOT NULL;
ALTER TABLE schedule ALTER COLUMN exceeds_term DROP NOT NULL;
