-- SPEC-026: achados da defesa. Append-only como a auditoria: histórico não se edita.

CREATE TABLE finding (
  id TEXT PRIMARY KEY,
  severity TEXT NOT NULL,
  detector TEXT NOT NULL,
  subject_kind TEXT NOT NULL,
  subject_id TEXT NOT NULL,
  title TEXT NOT NULL,
  rationale TEXT NOT NULL,
  signals_json TEXT NOT NULL,
  count INTEGER NOT NULL DEFAULT 1,
  first_seen TEXT NOT NULL,
  last_seen TEXT NOT NULL,
  acknowledged_at TEXT
);
CREATE INDEX idx_finding_seen ON finding(last_seen);
CREATE INDEX idx_finding_subject ON finding(subject_kind, subject_id);
CREATE TRIGGER finding_no_delete BEFORE DELETE ON finding BEGIN
  SELECT RAISE(ABORT, 'achado não se apaga');
END;
