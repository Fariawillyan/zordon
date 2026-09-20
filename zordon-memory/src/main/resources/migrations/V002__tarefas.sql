-- SPEC-023: planos duráveis e conclusão verificada (Planner §4). Transições só acrescentam.

CREATE TABLE task (
  id TEXT PRIMARY KEY,
  goal TEXT NOT NULL,
  origin TEXT NOT NULL,
  state TEXT NOT NULL,
  reason TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX idx_task_state ON task(state, updated_at);

CREATE TABLE task_step (
  task_id TEXT NOT NULL REFERENCES task(id),
  id TEXT NOT NULL,
  ord INTEGER NOT NULL,
  title TEXT NOT NULL,
  agent TEXT NOT NULL,
  depends_on TEXT NOT NULL,
  risk TEXT NOT NULL,
  done_when TEXT NOT NULL,
  state TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  result_json TEXT,
  PRIMARY KEY (task_id, id)
);

CREATE TABLE task_transition (
  id INTEGER PRIMARY KEY,
  task_id TEXT NOT NULL,
  step_id TEXT,
  from_state TEXT,
  to_state TEXT NOT NULL,
  reason TEXT,
  at TEXT NOT NULL
);
CREATE INDEX idx_transition_task ON task_transition(task_id, id);
CREATE TRIGGER task_transition_no_update BEFORE UPDATE ON task_transition BEGIN
  SELECT RAISE(ABORT, 'transições só acrescentam');
END;
CREATE TRIGGER task_transition_no_delete BEFORE DELETE ON task_transition BEGIN
  SELECT RAISE(ABORT, 'transições só acrescentam');
END;

CREATE TABLE verdict (
  id INTEGER PRIMARY KEY,
  task_id TEXT NOT NULL,
  step_id TEXT NOT NULL,
  agent TEXT,
  model TEXT,
  kind TEXT NOT NULL,
  verdict TEXT NOT NULL,
  reason TEXT,
  tokens INTEGER NOT NULL DEFAULT 0,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  at TEXT NOT NULL
);
CREATE INDEX idx_verdict_at ON verdict(at);
CREATE TRIGGER verdict_no_update BEFORE UPDATE ON verdict BEGIN
  SELECT RAISE(ABORT, 'vereditos só acrescentam');
END;
CREATE TRIGGER verdict_no_delete BEFORE DELETE ON verdict BEGIN
  SELECT RAISE(ABORT, 'vereditos só acrescentam');
END;
