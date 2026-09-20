-- SPEC-025: o estado de cada automação. A definição mora no TOML; aqui fica o que muda sozinho.

CREATE TABLE automation_state (
  id TEXT PRIMARY KEY,
  last_fired_at TEXT,
  fired INTEGER NOT NULL DEFAULT 0,
  failures INTEGER NOT NULL DEFAULT 0,
  disabled INTEGER NOT NULL DEFAULT 0,
  reason TEXT,
  updated_at TEXT NOT NULL
);
