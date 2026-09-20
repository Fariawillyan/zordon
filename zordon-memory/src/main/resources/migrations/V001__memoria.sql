-- SPEC-021: conversa como registro, fatos destilados e o índice lexical.

CREATE TABLE session (
  id TEXT PRIMARY KEY,
  title TEXT,
  started_at TEXT NOT NULL,
  last_active_at TEXT NOT NULL
);

CREATE TABLE message (
  id INTEGER PRIMARY KEY,
  session_id TEXT NOT NULL REFERENCES session(id),
  turn_id TEXT,
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  ts TEXT NOT NULL
);
CREATE INDEX idx_message_session ON message(session_id, id);
CREATE INDEX idx_message_turn ON message(turn_id);

CREATE TABLE fact (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  subject TEXT NOT NULL,
  content TEXT NOT NULL,
  normalized TEXT NOT NULL,
  confidence REAL NOT NULL,
  observed_at TEXT NOT NULL,
  expires_at TEXT,
  provenance TEXT NOT NULL,
  source TEXT NOT NULL,
  access_count INTEGER NOT NULL DEFAULT 0,
  last_accessed_at TEXT,
  superseded_by TEXT REFERENCES fact(id)
);
CREATE INDEX idx_fact_subject ON fact(subject);
CREATE INDEX idx_fact_kind ON fact(kind, observed_at);

CREATE VIRTUAL TABLE fact_fts USING fts5(
  content, subject, content='fact', content_rowid='rowid',
  tokenize='unicode61 remove_diacritics 2'
);
CREATE TRIGGER fact_ai AFTER INSERT ON fact BEGIN
  INSERT INTO fact_fts(rowid, content, subject) VALUES (new.rowid, new.content, new.subject);
END;
CREATE TRIGGER fact_ad AFTER DELETE ON fact BEGIN
  INSERT INTO fact_fts(fact_fts, rowid, content, subject) VALUES ('delete', old.rowid, old.content, old.subject);
END;
CREATE TRIGGER fact_au AFTER UPDATE OF content, subject ON fact BEGIN
  INSERT INTO fact_fts(fact_fts, rowid, content, subject) VALUES ('delete', old.rowid, old.content, old.subject);
  INSERT INTO fact_fts(rowid, content, subject) VALUES (new.rowid, new.content, new.subject);
END;

-- Relações (Memória §10): vazias até o M8.
CREATE TABLE entity (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  attrs_json TEXT
);
CREATE TABLE relation (
  from_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  to_id TEXT NOT NULL,
  source TEXT NOT NULL,
  confidence REAL,
  observed_at TEXT,
  PRIMARY KEY (from_id, kind, to_id, source)
);

CREATE TABLE distill_queue (
  turn_id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  tainted INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'pending',   -- pending | done | failed; nada é apagado
  attempts INTEGER NOT NULL DEFAULT 0,
  next_at TEXT NOT NULL,
  last_error TEXT
);
CREATE INDEX idx_distill_next ON distill_queue(state, next_at);
