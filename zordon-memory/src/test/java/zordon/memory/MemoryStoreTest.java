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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;

class MemoryStoreTest {

    @TempDir
    Path dir;

    /** Um relógio que o teste avança. */
    static final class Manual extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-19T15:00:00Z"));

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }

        void advance(Duration by) {
            now.updateAndGet(current -> current.plus(by));
        }
    }

    private final Manual clock = new Manual();
    private ZordonDatabase db;
    private SqliteMemoryStore store;

    private SqliteMemoryStore open() {
        db = new ZordonDatabase(dir.resolve("zordon.db"), clock);
        store = db.memory();
        return store;
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            db.close();
        }
    }

    private NewFact fact(FactKind kind, String subject, String content) {
        return new NewFact(kind, subject, content, 0.9, clock.instant(), null, "t1", "user", false);
    }

    private boolean tableExists(String name) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("zordon.db"));
                var query = db.prepareStatement("SELECT 1 FROM sqlite_master WHERE name = ?")) {
            query.setString(1, name);
            return query.executeQuery().next();
        }
    }

    @AcceptanceCriteria("SPEC-021/CA-1")
    @Test
    void migraPorVersaoCopiaAntesENaoApagaNada() throws Exception {
        open().session("s1", "Conversa", clock.instant());
        store.message("s1", "t1", "user", "oi", clock.instant());
        store.remember(fact(FactKind.PREFERENCE, "respostas", "prefere respostas curtas"));
        assertThat(db.schemaVersion()).isEqualTo(8);
        db.close();

        store = open();
        assertThat(store.history("s1", 10)).extracting(StoredLine::content).containsExactly("oi");
        assertThat(Files.exists(dir.resolve("zordon.db.bak.8"))).as("sem migração, sem cópia").isFalse();
        db.close();

        assertThatThrownBy(() -> new ZordonDatabase(dir.resolve("zordon.db"), clock, null,
                List.of("V001__memoria.sql", "V002__tarefas.sql", "V003__automacoes.sql",
                        "V004__orcamento_automacoes.sql", "V005__achados.sql", "V006__eventos_de_seguranca.sql",
                        "V007__conhecimento.sql", "V008__uso.sql", "V009__quebrada.sql")))
                .hasMessageContaining("V009__quebrada.sql falhou");
        assertThat(tableExists("teste_parcial")).as("rollback").isFalse();
        assertThat(Files.exists(dir.resolve("zordon.db.bak.8"))).as("a cópia veio antes").isTrue();

        db = new ZordonDatabase(dir.resolve("zordon.db"), clock, null,
                List.of("V001__memoria.sql", "V002__tarefas.sql", "V003__automacoes.sql",
                        "V004__orcamento_automacoes.sql", "V005__achados.sql", "V006__eventos_de_seguranca.sql",
                        "V007__conhecimento.sql", "V008__uso.sql", "V009__ok.sql"));
        store = db.memory();
        assertThat(db.schemaVersion()).isEqualTo(9);
        assertThat(store.facts(null, null, 10)).hasSize(1);
        assertThat(Files.list(dir).map(path -> path.getFileName().toString()).filter(name -> name.contains(".bak.")))
                .as("a segunda cópia não sobrescreve a primeira").hasSize(2);
        db.close();

        assertThatThrownBy(() -> new ZordonDatabase(dir.resolve("zordon.db"), clock))
                .hasMessageContaining("versão 9").hasMessageContaining("conhece até 8");
        store = null;
    }

    @Test
    void scriptComGatilhoViraComandosInteiros() {
        List<String> statements = Migrations.statements("""
                -- comentário
                CREATE TABLE a (x TEXT); -- no fim da linha
                CREATE TRIGGER t AFTER INSERT ON a BEGIN
                  INSERT INTO b VALUES (1);
                  INSERT INTO b VALUES (2);
                END;
                CREATE INDEX i ON a(x);
                """);
        assertThat(statements).hasSize(3);
        assertThat(statements.get(1)).startsWith("CREATE TRIGGER").endsWith("END;").contains("VALUES (2)");
    }

    @Test
    void fatoPrecisaDeProcedenciaENaoDuplica() {
        open();
        assertThatThrownBy(() -> new NewFact(FactKind.ENTITY, "x", "y", 0.5, clock.instant(), null, " ", "user", false))
                .hasMessageContaining("procedência");
        Fact first = store.remember(fact(FactKind.PREFERENCE, "Respostas", "Prefere respostas curtas."));
        Fact again = store.remember(fact(FactKind.PREFERENCE, "respostas", "prefere  respostas curtas"));
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(store.facts(null, null, 10)).hasSize(1);
        assertThat(first.provenance()).isEqualTo("t1");
    }

    @AcceptanceCriteria("SPEC-021/CA-3")
    @Test
    void correcaoSubstituiSemApagar() {
        open();
        Fact gradle = store.remember(fact(FactKind.PROJECT, "Aurora", "o projeto Aurora usa Gradle"));
        clock.advance(Duration.ofMinutes(1));
        Fact maven = store.remember(new NewFact(FactKind.PROJECT, "aurora", "o projeto Aurora usa Maven", 0.95,
                clock.instant(), null, "t2", "user", true));

        assertThat(store.facts(FactKind.PROJECT, "Aurora", 10)).extracting(Fact::id).containsExactly(maven.id());
        assertThat(store.search(RecallQuery.of("Aurora gradle", 10))).extracting(hit -> hit.fact().id())
                .containsExactly(maven.id());
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("zordon.db"));
                var query = db.prepareStatement("SELECT superseded_by FROM fact WHERE id = ?")) {
            query.setString(1, gradle.id());
            var row = query.executeQuery();
            assertThat(row.next()).as("o antigo continua na história").isTrue();
            assertThat(row.getString(1)).isEqualTo(maven.id());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void fatoExpiradoSaiDaBusca() {
        open();
        store.remember(new NewFact(FactKind.EVENT, "deploy", "deploy da API marcado", 0.9, clock.instant(),
                clock.instant().plus(Duration.ofHours(1)), "t1", "user", false));
        assertThat(store.search(RecallQuery.of("deploy", 5))).hasSize(1);
        clock.advance(Duration.ofHours(2));
        assertThat(store.search(RecallQuery.of("deploy", 5))).isEmpty();
    }

    @AcceptanceCriteria("SPEC-021/CA-4")
    @Test
    void buscaSemAcentoComFlexaoEJanelaDeOntem() {
        open();
        store.remember(fact(FactKind.PREFERENCE, "respostas", "prefere respostas curtas, sem preâmbulo"));
        clock.advance(Duration.ofDays(1));
        store.remember(fact(FactKind.EVENT, "projeto zordon", "trabalhou no projeto zordon (~/dev/zordon)"));

        assertThat(store.search(RecallQuery.of("preambulo", 5))).extracting(hit -> hit.fact().subject())
                .containsExactly("respostas");
        assertThat(store.search(RecallQuery.of("em que projeto trabalhamos?", 5)))
                .extracting(hit -> hit.fact().subject()).containsExactly("projeto zordon");
        assertThat(store.search(RecallQuery.of("", 5))).as("consulta vazia").isEmpty();

        clock.advance(Duration.ofDays(1));
        var ontem = TimeWindows.in("abra o projeto que trabalhamos ontem", ZoneOffset.UTC, clock.instant())
                .orElseThrow();
        assertThat(ontem.label()).isEqualTo("ontem");
        List<MemoryHit> hits = store.search(new RecallQuery("abra o projeto que trabalhamos ontem", Set.of(),
                ontem.since(), ontem.until(), 5));
        assertThat(hits).extracting(hit -> hit.fact().subject()).containsExactly("projeto zordon");
        var hoje = TimeWindows.in("o que fizemos hoje?", ZoneOffset.UTC, clock.instant()).orElseThrow();
        assertThat(store.search(new RecallQuery("projeto", Set.of(), hoje.since(), hoje.until(), 5))).isEmpty();
    }

    @AcceptanceCriteria("SPEC-021/CA-4")
    @Test
    void rrfFundeOEmbedderEReordenaPorRecenciaEAcessos() {
        AtomicReference<List<String>> nearest = new AtomicReference<>(List.of());
        db = new ZordonDatabase(dir.resolve("zordon.db"), clock, (query, limit) -> nearest.get());
        store = db.memory();
        Fact lexical = store.remember(fact(FactKind.ENTITY, "banco", "o banco da API é Postgres"));
        Fact both = store.remember(fact(FactKind.ENTITY, "banco principal", "banco principal roda no container db"));
        Fact vector = store.remember(fact(FactKind.ENTITY, "armazenamento", "os dados ficam num volume Docker"));
        nearest.set(List.of(vector.id(), both.id(), "f_esquecido"));

        List<MemoryHit> hits = store.search(RecallQuery.of("banco", 10));
        assertThat(hits).extracting(hit -> hit.fact().id()).containsExactlyInAnyOrder(lexical.id(), both.id(), vector.id());
        assertThat(hits.getFirst().fact().id()).as("nas duas listas").isEqualTo(both.id());

        // Recência: o mesmo evento, um velho e um novo.
        Fact old = store.remember(new NewFact(FactKind.EVENT, "build", "build do zordon falhou", 0.9,
                clock.instant().minus(Duration.ofDays(180)), null, "t0", "work", false));
        Fact recent = store.remember(fact(FactKind.EVENT, "build novo", "build do zordon falhou de novo"));
        nearest.set(List.of());
        assertThat(store.search(RecallQuery.of("build zordon", 10))).extracting(hit -> hit.fact().id())
                .containsSequence(recent.id(), old.id());
        assertThat(SqliteMemoryStore.weight(old, clock.instant())).isLessThan(0.3);
        Fact preference = store.remember(new NewFact(FactKind.PREFERENCE, "tema", "prefere tema escuro", 0.9,
                clock.instant().minus(Duration.ofDays(700)), null, "t0", "user", false));
        assertThat(SqliteMemoryStore.weight(preference, clock.instant()))
                .as("preferência não envelhece").isCloseTo(0.95, org.assertj.core.api.Assertions.within(1e-9));

        store.touched(List.of(preference.id(), preference.id()));
        assertThat(store.facts(FactKind.PREFERENCE, null, 5).getFirst().accessCount()).isEqualTo(2);
    }

    @AcceptanceCriteria("SPEC-021/CA-6")
    @Test
    void esquecerApagaDaTabelaEDoIndice() throws Exception {
        open();
        Fact secret = store.remember(fact(FactKind.ENTITY, "cofre", "a chave fica no cofre azul"));
        store.remember(fact(FactKind.ENTITY, "Aurora", "Aurora usa Java 25"));
        store.remember(fact(FactKind.PROJECT, "aurora", "Aurora fica em ~/dev/aurora"));

        assertThat(store.forget(secret.id())).isTrue();
        assertThat(store.forget(secret.id())).isFalse();
        assertThat(store.search(RecallQuery.of("cofre azul", 5))).isEmpty();
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("zordon.db"));
                var query = db.prepareStatement("SELECT count(*) FROM fact_fts WHERE fact_fts MATCH 'cofre'")) {
            var row = query.executeQuery();
            row.next();
            assertThat(row.getInt(1)).as("nem no índice").isZero();
        }
        assertThat(store.forgetSubject("AURORA")).isEqualTo(2);
        assertThat(store.facts(null, null, 10)).isEmpty();
    }

    @Test
    void conversaETurnoGravados() {
        open().session("s1", null, clock.instant());
        store.message("s1", "t1", "user", "que horas são?", clock.instant());
        store.message("s1", "t1", "assistant", "São 15h.", clock.instant());
        store.message("s1", "t2", "user", "obrigado", clock.instant());

        assertThat(store.turn("t1")).extracting(StoredLine::role).containsExactly("user", "assistant");
        assertThat(store.history("s1", 2)).extracting(StoredLine::content).containsExactly("São 15h.", "obrigado");
        assertThat(store.stats()).containsEntry("sessions", 1L).containsEntry("schemaVersion", 8);
    }

    @Test
    void filaDeDestilacaoComTentativasENadaApagado() {
        open();
        store.enqueueDistill("t1", "s1", false, clock.instant());
        store.enqueueDistill("t1", "s1", false, clock.instant());
        store.enqueueDistill("t2", "s1", true, clock.instant());
        assertThat(store.dueDistill(clock.instant(), 10)).extracting(MemoryStore.DistillJob::turnId)
                .containsExactly("t1", "t2");

        store.distilled("t1");
        store.distillFailed("t2", "modelo fora", clock.instant().plus(Duration.ofMinutes(5)), false);
        assertThat(store.dueDistill(clock.instant(), 10)).isEmpty();
        clock.advance(Duration.ofMinutes(6));
        assertThat(store.dueDistill(clock.instant(), 10)).singleElement().satisfies(job -> {
            assertThat(job.tainted()).isTrue();
            assertThat(job.attempts()).isEqualTo(1);
        });
        store.distillFailed("t2", "modelo fora", clock.instant(), true);
        assertThat(store.dueDistill(clock.instant().plus(Duration.ofDays(1)), 10)).isEmpty();
        assertThat(store.stats()).containsEntry("distillFailed", 1L).containsEntry("distillPending", 0L);
    }

    @AcceptanceCriteria("SPEC-023/CA-5")
    @Test
    void tarefaGuardaEtapasTransicoesEVereditosSemApagar() throws Exception {
        open();
        String id = db.tasks().createTask("veja por que a API caiu", "ui", List.of(
                new TaskStore.PlanStep("s1", "Ver os logs", "developer", List.of(), "green",
                        "{\"type\":\"judgement\",\"criterion\":\"a causa está nos logs\"}"),
                new TaskStore.PlanStep("s2", "Anotar a causa", "zordon", List.of("s1"), "green",
                        "{\"type\":\"human\",\"criterion\":\"anotado\"}")));
        db.tasks().taskState(id, "running", null);
        db.tasks().stepState(id, "s1", "running", null);
        db.tasks().stepResult(id, "s1", "{\"text\":\"postgres recusou\"}");
        db.tasks().stepState(id, "s1", "verifying", null);
        db.tasks().verdict(new TaskStore.Verdict(id, "s1", "developer", "m", "judgement", "pass", "ok", 1200, 900));
        db.tasks().stepState(id, "s1", "done", "pass");

        TaskStore.TaskView view = db.tasks().task(id).orElseThrow();
        assertThat(view.state()).isEqualTo("running");
        assertThat(view.steps()).extracting(TaskStore.StepView::state).containsExactly("done", "planned");
        assertThat(view.steps().getFirst().attempts()).isEqualTo(1);
        assertThat(view.steps().getFirst().resultJson()).contains("postgres");
        assertThat(view.steps().get(1).dependsOn()).containsExactly("s1");
        assertThat(db.tasks().interrupted()).extracting(TaskStore.TaskView::id).containsExactly(id);
        assertThat(db.tasks().taskStats()).containsEntry("byState", java.util.Map.of("running", 1L))
                .containsEntry("verdicts7d", java.util.Map.of("pass", 1L));

        try (var db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("zordon.db"));
                var statement = db.createStatement()) {
            var count = statement.executeQuery("SELECT count(*) FROM task_transition WHERE task_id = '" + id + "'");
            count.next();
            assertThat(count.getInt(1)).as("criada, rodando, e as três da etapa").isEqualTo(5);
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM task_transition"))
                    .hasMessageContaining("transições só acrescentam");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE verdict SET verdict = 'fail'"))
                    .hasMessageContaining("vereditos só acrescentam");
        }
    }

    @Test
    void estadoDaAutomacaoContaDisparosEFalhasSeguidas() {
        open();
        assertThat(db.automations().automationState("api")).isEqualTo(AutomationStateStore.State.fresh("api"));
        db.automations().automationFired("api", clock.instant());
        assertThat(db.automations().automationFinished("api", false)).isEqualTo(1);
        assertThat(db.automations().automationFinished("api", false)).isEqualTo(2);
        assertThat(db.automations().automationFinished("api", true)).as("sucesso zera").isZero();
        db.automations().automationDisabled("api", true, "20 falhas seguidas");
        AutomationStateStore.State state = db.automations().automationState("api");
        assertThat(state.disabled()).isTrue();
        assertThat(state.fired()).isEqualTo(1);
        assertThat(state.lastFiredAt()).isEqualTo(clock.instant());
        db.automations().automationDisabled("api", false, null);
        assertThat(db.automations().automationState("api").disabled()).isFalse();
    }

    @Test
    void consultaComCaracteresEstranhosNaoQuebra() {
        open();
        store.remember(fact(FactKind.ENTITY, "erro", "ERR_BRIDGE_UNAVAILABLE aparece quando o host cai"));
        assertThat(store.search(RecallQuery.of("\"*) OR NEAR( ERR_BRIDGE_UNAVAILABLE", 5))).hasSize(1);
        assertThat(SqliteMemoryStore.ftsQuery("o que é isso?")).isEmpty();
    }
}
