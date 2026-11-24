-- Tender intake from the console (ADR 0018): a person uploads the documents, an agent worker claims
-- the job, analyses it and reports back. The documents are kept as received, never changed.
CREATE TABLE intake (
    id            BIGSERIAL PRIMARY KEY,
    code          TEXT NOT NULL UNIQUE,
    title         TEXT NOT NULL,
    planned_start DATE NOT NULL,
    status        TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    documents     INT NOT NULL,
    bytes         BIGINT NOT NULL,
    uploaded_by   TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    tender_id     BIGINT REFERENCES tender (id),
    proposals     INT,
    message       TEXT
);
CREATE INDEX intake_status ON intake (status, id);

CREATE TABLE intake_document (
    id         BIGSERIAL PRIMARY KEY,
    intake_id  BIGINT NOT NULL REFERENCES intake (id),
    name       TEXT NOT NULL CHECK (name IN ('pliego', 'anexo', 'mediciones')),
    sha256     TEXT NOT NULL,
    size       BIGINT NOT NULL,
    content    BYTEA NOT NULL,
    UNIQUE (intake_id, name)
);

-- the documents are evidence of what was analysed: nobody rewrites them
CREATE FUNCTION intake_document_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'intake documents are immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER intake_document_no_update BEFORE UPDATE OR DELETE ON intake_document
    FOR EACH ROW EXECUTE FUNCTION intake_document_immutable();
