-- People who decide in the console (phase 15). Machines keep their role keys; a person signs in
-- with a personal token and gets a session cookie. Only SHA-256 hashes are stored.
CREATE TABLE person (
    id            BIGSERIAL PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE CHECK (username ~ '^[a-z][a-z0-9._-]{1,39}$'),
    display_name  TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('ESTIMATING', 'SITE_MANAGER', 'MANAGEMENT')),
    token_hash    TEXT NOT NULL CHECK (length(token_hash) = 64),
    active        BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE person_session (
    id_hash     TEXT PRIMARY KEY CHECK (length(id_hash) = 64),
    person_id   BIGINT NOT NULL REFERENCES person (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX person_session_person ON person_session (person_id);

-- A correction and its second approval are two decisions on the same proposal.
ALTER TABLE decision DROP CONSTRAINT decision_proposal_id_key;
ALTER TABLE decision ADD COLUMN person TEXT;
CREATE INDEX decision_proposal ON decision (proposal_id, id);

ALTER TABLE tender_stage_change ADD COLUMN person TEXT;

-- Rows written before this phase have no person; the hash chain treats them exactly as before.
ALTER TABLE audit_entry ADD COLUMN actor_person TEXT;

-- Four eyes: a correction waits here, still PENDING_REVIEW, until another person approves it.
ALTER TABLE proposal ADD COLUMN corrected_payload JSONB;
ALTER TABLE proposal ADD COLUMN corrected_by TEXT;
