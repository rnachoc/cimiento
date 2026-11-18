CREATE TABLE project (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tender (
    id                    BIGSERIAL PRIMARY KEY,
    project_id            BIGINT NOT NULL REFERENCES project (id),
    code                  TEXT NOT NULL UNIQUE,
    title                 TEXT NOT NULL,
    contract_term_months  INT NOT NULL CHECK (contract_term_months > 0),
    planned_start         DATE NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Company rates (Constructora Brisa, fictional). Price per canonical unit, VAT excluded.
CREATE TABLE rate (
    code          TEXT PRIMARY KEY,
    description   TEXT NOT NULL,
    activity      TEXT NOT NULL,
    unit          TEXT NOT NULL,
    unit_price    NUMERIC(14, 2) NOT NULL CHECK (unit_price >= 0),
    rate_per_day  NUMERIC(14, 2) NOT NULL CHECK (rate_per_day > 0)
);

CREATE TABLE proposal (
    id                BIGSERIAL PRIMARY KEY,
    tender_id         BIGINT NOT NULL REFERENCES tender (id),
    type              TEXT NOT NULL,
    status            TEXT NOT NULL,
    route             TEXT,
    payload           JSONB NOT NULL,
    executed_payload  JSONB,
    citations         JSONB NOT NULL,
    signals           JSONB NOT NULL,
    rule_results      JSONB NOT NULL,
    routing_reasons   JSONB NOT NULL,
    created_by        TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at        TIMESTAMPTZ
);
CREATE INDEX proposal_status_idx ON proposal (status);

CREATE TABLE idempotency_key (
    id            BIGSERIAL PRIMARY KEY,
    role          TEXT NOT NULL,
    key           TEXT NOT NULL,
    request_hash  TEXT NOT NULL CHECK (length(request_hash) = 64),
    proposal_id   BIGINT NOT NULL REFERENCES proposal (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (role, key)
);

CREATE TABLE decision (
    id                 BIGSERIAL PRIMARY KEY,
    proposal_id        BIGINT NOT NULL UNIQUE REFERENCES proposal (id),
    outcome            TEXT NOT NULL,
    comment            TEXT,
    corrected_payload  JSONB,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE schedule (
    id                 BIGSERIAL PRIMARY KEY,
    tender_id          BIGINT NOT NULL REFERENCES tender (id),
    proposal_id        BIGINT NOT NULL UNIQUE REFERENCES proposal (id),
    duration_days      INT NOT NULL,
    start_date         DATE NOT NULL,
    end_date           DATE NOT NULL,
    contract_deadline  DATE NOT NULL,
    exceeds_term       BOOLEAN NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE schedule_activity (
    id           BIGSERIAL PRIMARY KEY,
    schedule_id  BIGINT NOT NULL REFERENCES schedule (id),
    code         TEXT NOT NULL,
    name         TEXT NOT NULL,
    duration     INT NOT NULL CHECK (duration > 0),
    es           INT NOT NULL,
    ef           INT NOT NULL,
    ls           INT NOT NULL,
    lf           INT NOT NULL,
    total_float  INT NOT NULL,
    critical     BOOLEAN NOT NULL,
    UNIQUE (schedule_id, code)
);

CREATE TABLE schedule_link (
    id           BIGSERIAL PRIMARY KEY,
    schedule_id  BIGINT NOT NULL REFERENCES schedule (id),
    from_code    TEXT NOT NULL,
    to_code      TEXT NOT NULL,
    lag          INT NOT NULL CHECK (lag >= 0)
);

CREATE TABLE progress (
    id             BIGSERIAL PRIMARY KEY,
    tender_id      BIGINT NOT NULL REFERENCES tender (id),
    proposal_id    BIGINT NOT NULL REFERENCES proposal (id),
    week           INT NOT NULL CHECK (week > 0),
    activity_code  TEXT NOT NULL,
    planned_pct    INT NOT NULL CHECK (planned_pct BETWEEN 0 AND 100),
    actual_pct     INT NOT NULL CHECK (actual_pct BETWEEN 0 AND 100)
);

-- Append-only audit log, hash-chained. Rows can only be inserted.
CREATE TABLE audit_entry (
    seq          BIGSERIAL PRIMARY KEY,
    created_at   TIMESTAMPTZ NOT NULL,
    actor_role   TEXT NOT NULL,
    action       TEXT NOT NULL,
    entity_type  TEXT NOT NULL,
    entity_id    TEXT NOT NULL,
    details      TEXT NOT NULL,
    prev_hash    TEXT NOT NULL CHECK (length(prev_hash) = 64),
    hash         TEXT NOT NULL UNIQUE CHECK (length(hash) = 64)
);

CREATE FUNCTION audit_entry_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_entry is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_entry_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION audit_entry_is_append_only();

CREATE TRIGGER audit_entry_no_truncate
    BEFORE TRUNCATE ON audit_entry
    FOR EACH STATEMENT EXECUTE FUNCTION audit_entry_is_append_only();
