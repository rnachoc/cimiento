-- Work calendar per tender (ADR 0017). NONE keeps Monday to Friday without holidays, the default
-- the generated ground truth is built on; local holidays are a JSON array of {date, name}.
ALTER TABLE tender ADD COLUMN calendar TEXT NOT NULL DEFAULT 'NONE' CHECK (calendar IN ('NONE', 'ES_NATIONAL'));
ALTER TABLE tender ADD COLUMN local_holidays JSONB NOT NULL DEFAULT '[]';
