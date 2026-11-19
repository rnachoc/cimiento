-- Monthly certifications of site work (phase 18). The core calculates them from the approved
-- budget and the weekly progress (rules/Certification.java); a row is a frozen snapshot.
CREATE TABLE certification (
    id          BIGSERIAL PRIMARY KEY,
    tender_id   BIGINT NOT NULL REFERENCES tender (id),
    number      INT NOT NULL CHECK (number > 0),
    month       TEXT NOT NULL CHECK (month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    cut_week    INT NOT NULL CHECK (cut_week > 0),
    lines       JSONB NOT NULL,
    budget      NUMERIC(14, 2) NOT NULL,
    to_date     NUMERIC(14, 2) NOT NULL,
    previous    NUMERIC(14, 2) NOT NULL,
    period      NUMERIC(14, 2) NOT NULL,
    retention   NUMERIC(14, 2) NOT NULL,
    net         NUMERIC(14, 2) NOT NULL,
    vat         NUMERIC(14, 2) NOT NULL,
    payable     NUMERIC(14, 2) NOT NULL,
    issued_by   TEXT NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tender_id, number),
    UNIQUE (tender_id, month)
);

-- A certification is never changed or removed: a mistake is corrected by the next one.
CREATE FUNCTION certification_is_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'certification rows are immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER certification_no_update BEFORE UPDATE OR DELETE ON certification
    FOR EACH ROW EXECUTE FUNCTION certification_is_immutable();
