-- SPEC-029: quanto o dia custou, por provider, modelo e ator.

CREATE TABLE usage_daily (
  day TEXT NOT NULL,
  provider TEXT NOT NULL,
  model TEXT NOT NULL,
  actor TEXT NOT NULL,
  input_tokens INTEGER NOT NULL DEFAULT 0,
  output_tokens INTEGER NOT NULL DEFAULT 0,
  calls INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, provider, model, actor)
);
CREATE INDEX idx_usage_day ON usage_daily(day);
