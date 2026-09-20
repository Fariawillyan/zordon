-- SPEC-027: o histórico da defesa. Append-only com cadeia de hash, como a auditoria.

CREATE TABLE security_event (
  id INTEGER PRIMARY KEY,
  event_id TEXT NOT NULL UNIQUE,
  ts TEXT NOT NULL,
  severity TEXT NOT NULL,
  detector TEXT NOT NULL,
  subject TEXT NOT NULL,
  finding_id TEXT,
  proposed TEXT NOT NULL,
  executed TEXT NOT NULL,
  outcome TEXT NOT NULL,
  authorization_kind TEXT NOT NULL,
  rollback_available INTEGER NOT NULL DEFAULT 0,
  user_message_id TEXT,
  prev_hash TEXT NOT NULL,
  hash TEXT NOT NULL
);
CREATE INDEX idx_security_event_ts ON security_event(ts);
CREATE TRIGGER security_event_no_update BEFORE UPDATE ON security_event BEGIN
  SELECT RAISE(ABORT, 'evento de segurança não se altera');
END;
CREATE TRIGGER security_event_no_delete BEFORE DELETE ON security_event BEGIN
  SELECT RAISE(ABORT, 'evento de segurança não se apaga');
END;
