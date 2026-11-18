-- Bid pipeline: every tender has a stage; moves are validated by the core (rules/Pipeline.java)
-- and audited (TENDER_STAGE_CHANGED).
ALTER TABLE tender ADD COLUMN stage TEXT NOT NULL DEFAULT 'TO_REVIEW'
    CHECK (stage IN ('TO_REVIEW', 'UNDER_REVIEW', 'DECISION_PENDING', 'IN_PREPARATION', 'SUBMITTED',
                     'AWARDED', 'LOST', 'DISCARDED'));
ALTER TABLE tender ADD COLUMN stage_changed_at TIMESTAMPTZ;

-- History of stage moves with the reviewer's reason (the audit row carries only from and to).
CREATE TABLE tender_stage_change (
    id          BIGSERIAL PRIMARY KEY,
    tender_id   BIGINT NOT NULL REFERENCES tender (id),
    from_stage  TEXT NOT NULL,
    to_stage    TEXT NOT NULL,
    note        TEXT,
    actor_role  TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tender_stage_change_tender ON tender_stage_change (tender_id, id);
