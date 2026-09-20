-- SPEC-025: o orçamento diário não zera quando o núcleo reinicia.
CREATE TABLE automation_budget (
  day TEXT PRIMARY KEY,
  tokens INTEGER NOT NULL DEFAULT 0
);
