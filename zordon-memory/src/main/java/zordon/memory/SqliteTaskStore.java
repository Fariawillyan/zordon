/*
 * Copyright 2026 Willyan Faria
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zordon.memory;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Os planos duráveis e os vereditos (SPEC-023).
 *
 * <p>Parte do {@link ZordonDatabase}: divisão feita na SPEC-035, quando os sete
 * contratos deixaram de morar numa classe só. O banco e o monitor continuam
 * únicos — o que se separou foi a responsabilidade.
 */
public final class SqliteTaskStore implements TaskStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteTaskStore.class);

    private static final SecureRandom random = new SecureRandom();

    private final Sql sql;
    private final Clock clock;

    SqliteTaskStore(ZordonDatabase database) {
        this.sql = database.sql();
        this.clock = database.clock();
    }

    // tarefas (SPEC-023) ---------------------------------------------------

    @Override
    public String createTask(String goal, String origin, List<PlanStep> steps) {
        synchronized (sql) {
            Instant now = clock.instant();
            String id = "task_" + Long.toString(now.toEpochMilli(), 36) + Long.toString(random.nextLong() & 0xfffffL, 36);
            try {
                sql.connection().setAutoCommit(false);
                try (PreparedStatement task = sql.prepare(
                        "INSERT INTO task (id, goal, origin, state, created_at, updated_at) VALUES (?, ?, ?, 'planned', ?, ?)");
                        PreparedStatement step = sql.prepare(
                                "INSERT INTO task_step (task_id, id, ord, title, agent, depends_on, risk, done_when, state) "
                                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'planned')")) {
                    task.setString(1, id);
                    task.setString(2, goal);
                    task.setString(3, origin);
                    task.setString(4, now.toString());
                    task.setString(5, now.toString());
                    task.executeUpdate();
                    for (int i = 0; i < steps.size(); i++) {
                        PlanStep plan = steps.get(i);
                        step.setString(1, id);
                        step.setString(2, plan.id());
                        step.setInt(3, i);
                        step.setString(4, plan.title());
                        step.setString(5, plan.agent());
                        step.setString(6, TaskJson.write(plan.dependsOn()));
                        step.setString(7, plan.risk());
                        step.setString(8, plan.doneWhen());
                        step.addBatch();
                    }
                    step.executeBatch();
                    transitionRow(id, null, null, "planned", "plano com " + steps.size() + " etapa(s)", now);
                    sql.connection().commit();
                } catch (SQLException e) {
                    sql.connection().rollback();
                    throw new SQLException(e.getMessage(), e);
                } finally {
                    sql.connection().setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw Sql.failure("tarefa", e);
            }
            log.info("tarefa {} criada com {} etapa(s)", id, steps.size());
            return id;
        }
    }

    @Override
    public void taskState(String taskId, String to, String reason) {
        synchronized (sql) {
            String from = sql.scalar("SELECT state FROM task WHERE id = ?", taskId);
            Instant now = clock.instant();
            try (PreparedStatement update = sql.prepare(
                    "UPDATE task SET state = ?, reason = ?, updated_at = ? WHERE id = ?")) {
                update.setString(1, to);
                update.setString(2, reason);
                update.setString(3, now.toString());
                update.setString(4, taskId);
                update.executeUpdate();
                transitionRow(taskId, null, from, to, reason, now);
            } catch (SQLException e) {
                throw Sql.failure("estado da tarefa", e);
            }
        }
    }

    @Override
    public void stepState(String taskId, String stepId, String to, String reason) {
        synchronized (sql) {
            Instant now = clock.instant();
            String from;
            try (PreparedStatement query = sql.prepare("SELECT state FROM task_step WHERE task_id = ? AND id = ?")) {
                query.setString(1, taskId);
                query.setString(2, stepId);
                try (ResultSet row = query.executeQuery()) {
                    from = row.next() ? row.getString(1) : null;
                }
            } catch (SQLException e) {
                throw Sql.failure("estado da etapa", e);
            }
            try (PreparedStatement update = sql.prepare(
                    "UPDATE task_step SET state = ?, attempts = attempts + ? WHERE task_id = ? AND id = ?");
                    PreparedStatement touch = sql.prepare("UPDATE task SET updated_at = ? WHERE id = ?")) {
                update.setString(1, to);
                update.setInt(2, "running".equals(to) ? 1 : 0);
                update.setString(3, taskId);
                update.setString(4, stepId);
                update.executeUpdate();
                touch.setString(1, now.toString());
                touch.setString(2, taskId);
                touch.executeUpdate();
                transitionRow(taskId, stepId, from, to, reason, now);
            } catch (SQLException e) {
                throw Sql.failure("estado da etapa", e);
            }
        }
    }

    @Override
    public void stepResult(String taskId, String stepId, String resultJson) {
        synchronized (sql) {
            try (PreparedStatement update = sql.prepare(
                    "UPDATE task_step SET result_json = ? WHERE task_id = ? AND id = ?")) {
                update.setString(1, resultJson);
                update.setString(2, taskId);
                update.setString(3, stepId);
                update.executeUpdate();
            } catch (SQLException e) {
                throw Sql.failure("resultado da etapa", e);
            }
        }
    }

    @Override
    public void completeStep(String taskId, String stepId, String resultJson, String state, String reason) {
        synchronized (sql) {
            try {
                sql.connection().setAutoCommit(false);
                try {
                    stepResult(taskId, stepId, resultJson);
                    stepState(taskId, stepId, state, reason);
                    sql.connection().commit();
                } catch (RuntimeException | SQLException e) {
                    sql.connection().rollback();
                    throw e;
                } finally {
                    sql.connection().setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw Sql.failure("conclusão da etapa", e);
            }
        }
    }

    @Override
    public void verdict(Verdict verdict) {
        synchronized (sql) {
            try (PreparedStatement insert = sql.prepare(
                    "INSERT INTO verdict (task_id, step_id, agent, model, kind, verdict, reason, tokens, duration_ms, at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, verdict.taskId());
                insert.setString(2, verdict.stepId());
                insert.setString(3, verdict.agent());
                insert.setString(4, verdict.model());
                insert.setString(5, verdict.kind());
                insert.setString(6, verdict.verdict());
                insert.setString(7, verdict.reason());
                insert.setLong(8, verdict.tokens());
                insert.setLong(9, verdict.durationMs());
                insert.setString(10, clock.instant().toString());
                insert.executeUpdate();
            } catch (SQLException e) {
                throw Sql.failure("veredito", e);
            }
        }
    }

    @Override
    public List<TaskView> tasks(int limit) {
        synchronized (sql) {
            List<String> ids = new ArrayList<>();
            sql.ids("SELECT id FROM task ORDER BY created_at DESC LIMIT " + Math.max(1, Math.min(limit, 200)), List.of(), ids);
            return ids.stream().map(this::task).flatMap(java.util.Optional::stream).toList();
        }
    }

    @Override
    public java.util.Optional<TaskView> task(String taskId) {
        synchronized (sql) {
            try (PreparedStatement query = sql.prepare(
                    "SELECT id, goal, origin, state, reason, created_at, updated_at FROM task WHERE id = ?");
                    PreparedStatement steps = sql.prepare(
                            "SELECT id, ord, title, agent, depends_on, risk, done_when, state, attempts, result_json "
                                    + "FROM task_step WHERE task_id = ? ORDER BY ord")) {
                query.setString(1, taskId);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) {
                        return java.util.Optional.empty();
                    }
                    steps.setString(1, taskId);
                    List<StepView> views = new ArrayList<>();
                    try (ResultSet step = steps.executeQuery()) {
                        while (step.next()) {
                            views.add(new StepView(step.getString(1), step.getInt(2), step.getString(3), step.getString(4),
                                    TaskJson.read(step.getString(5)), step.getString(6),
                                    step.getString(7), step.getString(8), step.getInt(9), step.getString(10)));
                        }
                    }
                    return java.util.Optional.of(new TaskView(row.getString(1), row.getString(2), row.getString(3),
                            row.getString(4), row.getString(5), Instant.parse(row.getString(6)),
                            Instant.parse(row.getString(7)), List.copyOf(views)));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("memória (tarefa): " + e.getMessage(), e);
            }
        }
    }

    @Override
    public List<TaskView> interrupted() {
        synchronized (sql) {
            List<String> ids = new ArrayList<>();
            sql.ids("SELECT id FROM task WHERE state = 'running' ORDER BY created_at", List.of(), ids);
            return ids.stream().map(this::task).flatMap(java.util.Optional::stream).toList();
        }
    }

    @Override
    public Map<String, Object> taskStats() {
        synchronized (sql) {
            return TaskStats.read(sql, clock);
        }
    }

    private void transitionRow(String taskId, String stepId, String from, String to, String reason, Instant at)
            throws SQLException {
        try (PreparedStatement insert = sql.prepare(
                "INSERT INTO task_transition (task_id, step_id, from_state, to_state, reason, at) VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, taskId);
            insert.setString(2, stepId);
            insert.setString(3, from);
            insert.setString(4, to);
            insert.setString(5, reason);
            insert.setString(6, at.toString());
            insert.executeUpdate();
        }
        log.info("tarefa {}{}: {} → {}{}", taskId, stepId == null ? "" : "/" + stepId, from, to,
                reason == null ? "" : " (" + reason + ")");
    }
}
