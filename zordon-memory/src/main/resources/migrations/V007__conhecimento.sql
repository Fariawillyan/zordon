-- SPEC-028: a documentação do projeto como índice consultável. Dado derivado: some e volta.

CREATE TABLE doc_chunk (
  id INTEGER PRIMARY KEY,
  path TEXT NOT NULL,
  file_hash TEXT NOT NULL,
  heading TEXT NOT NULL,
  ord INTEGER NOT NULL,
  level TEXT,
  text TEXT NOT NULL,
  indexed_at TEXT NOT NULL
);
CREATE INDEX idx_doc_chunk_path ON doc_chunk(path);

CREATE VIRTUAL TABLE doc_fts USING fts5(
  text, heading, path, content='doc_chunk', content_rowid='id',
  tokenize='unicode61 remove_diacritics 2'
);
CREATE TRIGGER doc_ai AFTER INSERT ON doc_chunk BEGIN
  INSERT INTO doc_fts(rowid, text, heading, path) VALUES (new.id, new.text, new.heading, new.path);
END;
CREATE TRIGGER doc_ad AFTER DELETE ON doc_chunk BEGIN
  INSERT INTO doc_fts(doc_fts, rowid, text, heading, path) VALUES ('delete', old.id, old.text, old.heading, old.path);
END;

CREATE TABLE doc_file (
  path TEXT PRIMARY KEY,
  file_hash TEXT NOT NULL,
  chunks INTEGER NOT NULL,
  indexed_at TEXT NOT NULL
);
