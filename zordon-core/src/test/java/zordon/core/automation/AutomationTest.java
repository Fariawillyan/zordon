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
package zordon.core.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.ai.ModelPolicy;
import zordon.ai.registry.ProviderRegistry;
import zordon.api.event.EventType;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RiskLevel;
import zordon.api.trace.AcceptanceCriteria;
import zordon.core.agents.AgentRegistry;
import zordon.core.agents.AgentRunner;
import zordon.core.chat.PromptComposer;
import zordon.core.chat.ToolLoopTestSupport;
import zordon.core.event.ZordonEventBus;
import zordon.core.monitor.SystemSampler;
import zordon.core.notify.NotificationCenter;
import zordon.core.tools.ModelToolCaller;
import zordon.core.tools.SkillRuntime;
import zordon.core.tools.Tool;
import zordon.core.tools.ToolException;
import zordon.core.tools.ToolResult;
import zordon.memory.SqliteMemoryStore;
import zordon.memory.TaskStore;
import zordon.security.CommandValidator;
import zordon.security.DefaultPermissionEngine;
import zordon.security.Gatekeeper;
import zordon.security.PathPolicy;
import zordon.security.PermissionEngine;
import zordon.security.Redactor;
import zordon.security.SqliteAuditLog;

class AutomationTest {
    @TempDir Path home;
    private final AtomicLong nanos = new AtomicLong(1);
    private final AtomicBoolean lockdown = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> notices = new CopyOnWriteArrayList<>();
    private Clock clock = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneId.of("UTC"));
    private SqliteMemoryStore store;
    private SqliteAuditLog audit;
    private ZordonEventBus bus;
    private SkillRuntime runtime;
    private AgentRegistry registry;
    private AgentRunner runner;
    private WorkflowEngine workflow;
    private AutomationEngine engine;
    private ToolLoopTestSupport.Scripted provider;

    @BeforeEach void setup() {
        store = new SqliteMemoryStore(home.resolve("zordon.db"), clock);
        audit = new SqliteAuditLog(home.resolve("audit.db"), new Redactor(), clock);
        bus = new ZordonEventBus("test", clock);
        PermissionEngine.Approver deny = (a, b, c, d, e) -> CompletableFuture.completedFuture(PermissionEngine.Approval.DENY);
        Gatekeeper gatekeeper = new Gatekeeper(new DefaultPermissionEngine(
                PathPolicy.defaults(home.toString(), List.of("~"), List.of("~")), new CommandValidator(Map.of()),
                new Redactor(), () -> deny), audit);
        runtime = new SkillRuntime(gatekeeper, bus, lockdown::get);
        registry = new AgentRegistry(home.resolve("agents"));
        provider = new ToolLoopTestSupport.Scripted();
        runner = new AgentRunner(ProviderRegistry.of(Map.of(ModelPolicy.DEFAULT_PROVIDER, provider), ModelPolicy.defaults()),
                new PromptComposer(), new ModelToolCaller(runtime), bus);
        workflow = new WorkflowEngine(store, runtime, registry, runner,
                (spec, title, body, severity) -> notices.add(title + ":" + body), bus, clock, nanos::get);
        engine = new AutomationEngine(home.resolve("automations"), store, store, runtime, registry, workflow,
                (spec, title, body, severity) -> notices.add(title + ":" + body), bus, lockdown::get,
                () -> SystemSampler.Snapshot.EMPTY, clock, nanos::get);
        runtime.register(tool("test.check", RiskLevel.GREEN, () -> new ToolResult("API caiu", Map.of("status", 503))));
    }

    @AfterEach void close() {
        engine.close();
        bus.close();
        audit.close();
        store.close();
    }

    private Tool tool(String name, RiskLevel risk, java.util.concurrent.Callable<ToolResult> run) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "ferramenta de teste"; }
            @Override public RiskLevel baseRisk() { return risk; }
            @Override public Set<Effect> effects() { return Set.of(); }
            @Override public ActionDescriptor describe(Map<String, Object> args) {
                return new ActionDescriptor(name, args, risk, Set.of(), List.of(), 0, List.of(), name);
            }
            @Override public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                calls.incrementAndGet();
                return run.call();
            }
        };
    }

    static Map<String, Object> raw(String id, List<Map<String, Object>> steps) {
        return Map.of("id", id, "name", "Teste " + id, "trigger", Map.of("type", "interval", "every", "PT1M"), "step", steps);
    }

    static Map<String, Object> notifyStep(String id, String title) {
        return Map.of("id", id, "notify", Map.of("title", title, "body", "{{event.container}}"));
    }

    private String approve(Map<String, Object> raw) {
        String proposal = (String) engine.propose(raw).get("proposalId");
        return engine.approve(proposal);
    }

    private void finished(String id) throws Exception {
        await(() -> ((Number) engine.diagnostics().get("running")).intValue() == 0);
        assertThat(store.task(id).orElseThrow().state()).isIn("done", "failed", "blocked", "cancelled");
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= end) { throw new AssertionError("prazo do teste expirou"); }
            Thread.sleep(10);
        }
    }

    @Test @AcceptanceCriteria("SPEC-025/CA-1")
    void propostaNaoGravaAteAprovacaoENaoSobrescreveArquivo() throws Exception {
        Map<String, Object> raw = raw("api", List.of(notifyStep("tell", "API")));
        String proposal = (String) engine.propose(raw).get("proposalId");
        assertThat(home.resolve("automations/api.toml")).doesNotExist();
        assertThat(engine.approve(proposal)).isEqualTo("api");
        assertThat(AutomationSpec.parseToml(Files.readString(home.resolve("automations/api.toml"))))
                .isEqualTo(AutomationSpec.fromMap(raw));
        assertThatThrownBy(() -> engine.propose(raw)).hasMessageContaining("já existe");
        assertThat(engine.list().get("proposals")).isEqualTo(List.of());
        engine.enable("api", false);
        engine.reload();
        assertThat(engine.fire("api", Map.of())).isEmpty();
        assertThat(home.resolve("automations/api.toml")).exists();
        engine.enable("api", true);
        String task = engine.fire("api", Map.of()).orElseThrow();
        finished(task);
    }

    @Test @AcceptanceCriteria("SPEC-025/CA-2")
    void workflowInterpolaAvaliaWhenEGravaResultados() {
        AutomationSpec spec = AutomationSpec.fromMap(raw("api", List.of(
                Map.of("id", "check", "tool", "test.check"),
                Map.of("id", "tell", "when", "check.status != 200", "notify", Map.of("title", "Falhou", "body", "{{check.text}}")),
                Map.of("id", "quiet", "when", "check.status == 200", "notify", Map.of("title", "Saudável")))));
        WorkflowEngine.Outcome result = workflow.run(spec, Map.of());
        assertThat(result.ok()).isTrue();
        assertThat(calls).hasValue(1);
        assertThat(notices).containsExactly("Falhou:API caiu");
        assertThat(store.task(result.taskId()).orElseThrow().steps()).extracting(TaskStore.StepView::state)
                .containsExactly("done", "done", "done", "skipped");
    }

    @Test @AcceptanceCriteria("SPEC-025/CA-2")
    void retomadaUsaDefinicaoOriginalSemRepetirPassoConcluido() {
        AutomationSpec original = AutomationSpec.fromMap(raw("api", List.of(Map.of("id", "check", "tool", "test.check"),
                Map.of("id", "tell", "notify", Map.of("title", "Original", "body", "{{check.text}} {{event.container}}")))));
        String task = workflow.prepare(original, Map.of("container", "api"));
        store.completeStep(task, "check", "{\"text\":\"salvo antes da queda\"}", "done", null);
        store.taskState(task, "blocked", "reiniciou");
        store.close();
        store = new SqliteMemoryStore(home.resolve("zordon.db"), clock);
        WorkflowEngine restarted = new WorkflowEngine(store, runtime, registry, runner,
                (spec, title, body, severity) -> notices.add(title + ":" + body), bus, clock, nanos::get);
        AutomationSpec changed = AutomationSpec.fromMap(raw("api", List.of(notifyStep("new", "Alterada"))));
        assertThat(restarted.resume(changed, task).ok()).isTrue();
        assertThat(calls).hasValue(0);
        assertThat(notices).containsExactly("Original:salvo antes da queda api");
        assertThatThrownBy(() -> restarted.resume(changed, task)).hasMessageContaining("não retomável");
    }

    @Test void retryEOnErrorContinuamMasContamFalha() {
        AtomicInteger attempts = new AtomicInteger();
        runtime.register(tool("test.flaky", RiskLevel.GREEN, () -> {
            if (attempts.incrementAndGet() == 1) { throw new ToolException("temporário"); }
            return ToolResult.of("recuperado");
        }));
        AutomationSpec retry = AutomationSpec.fromMap(raw("retry", List.of(Map.of("id", "check", "tool", "test.flaky",
                "retry", Map.of("attempts", 2, "backoff", "PT0.001S")))));
        assertThat(workflow.run(retry, Map.of()).ok()).isTrue();
        assertThat(attempts).hasValue(2);
        runtime.register(tool("test.fail", RiskLevel.GREEN, () -> { throw new ToolException("permanente"); }));
        for (String onError : List.of("stop", "skip", "notify")) {
            notices.clear();
            AutomationSpec failure = AutomationSpec.fromMap(raw("fail", List.of(
                    Map.of("id", "check", "tool", "test.fail", "onError", onError), notifyStep("tell", "Depois"))));
            assertThat(workflow.run(failure, Map.of()).ok()).isFalse();
            assertThat(notices.stream().anyMatch(text -> text.startsWith("Depois"))).isEqualTo(!onError.equals("stop"));
            assertThat(notices.stream().anyMatch(text -> text.contains("não completou"))).isEqualTo(!onError.equals("skip"));
        }
    }

    @Test @AcceptanceCriteria("SPEC-025/CA-5")
    void yellowRecusadoLockdownPulaEVinteFalhasDesativam() throws Exception {
        runtime.register(tool("test.write", RiskLevel.YELLOW, () -> ToolResult.of("efeito")));
        assertThatThrownBy(() -> engine.propose(raw("write", List.of(Map.of("id", "write", "tool", "test.write")))))
                .hasMessageContaining("GREEN");
        runtime.register(tool("test.fail", RiskLevel.GREEN, () -> { throw new ToolException("falha"); }));
        approve(raw("bad", List.of(Map.of("id", "check", "tool", "test.fail"))));
        lockdown.set(true);
        assertThat(engine.fire("bad", Map.of())).isEmpty();
        assertThat(store.tasks(10)).isEmpty();
        lockdown.set(false);
        for (int i = 0; i < 20; i++) { finished(engine.fire("bad", Map.of()).orElseThrow()); }
        assertThat(store.automationState("bad").disabled()).isTrue();
        assertThat(store.automationState("bad").failures()).isEqualTo(20);
        assertThat(engine.fire("bad", Map.of())).isEmpty();
        assertThat(calls).hasValue(20);
        assertThat(notices).anyMatch(text -> text.contains("20 falhas seguidas"));
    }

    @Test @AcceptanceCriteria("SPEC-025/CA-3")
    void eventoCasaComCamposEEvitaExecucoesSobrepostas() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime.register(tool("test.wait", RiskLevel.GREEN, () -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) { throw new ToolException("timeout teste"); }
            return ToolResult.of("logs");
        }));
        approve(Map.of("id", "container", "name", "Container caiu", "trigger",
                Map.of("type", "event", "event", "CONTAINER_EVENT", "match", Map.of("container", "api", "action", "die")),
                "step", List.of(Map.of("id", "logs", "tool", "test.wait"), notifyStep("tell", "Caiu"))));
        engine.event(bus.publish(EventType.CONTAINER_EVENT, Map.of("container", "outro", "action", "die")));
        assertThat(store.tasks(10)).isEmpty();
        engine.event(bus.publish(EventType.CONTAINER_EVENT, Map.of("container", "api", "action", "die")));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        engine.event(bus.publish(EventType.CONTAINER_EVENT, Map.of("container", "api", "action", "die")));
        release.countDown();
        finished(store.tasks(1).getFirst().id());
        assertThat(store.tasks(10)).hasSize(1);
        assertThat(notices).contains("Caiu:api");
    }

    @Test @AcceptanceCriteria("SPEC-025/CA-5")
    void orcamentoDiarioSobreviveReinicioESoViraNoDiaSeguinte() {
        LocalDate today = LocalDate.now(clock);
        assertThat(store.reserveAutomationTokens(today, 200_000, 200_000)).isEqualTo(200_000);
        store.close();
        store = new SqliteMemoryStore(home.resolve("zordon.db"), clock);
        WorkflowEngine restarted = new WorkflowEngine(store, runtime, registry, runner,
                (s, t, b, v) -> { }, bus, clock, nanos::get);
        AutomationSpec spec = AutomationSpec.fromMap(raw("agent", List.of(Map.of("id", "think", "agent", "zordon", "task", "Explique"))));
        assertThat(restarted.run(spec, Map.of()).ok()).isFalse();
        assertThat(provider.chats).isEmpty();
        assertThat(restarted.tokensToday()).isEqualTo(200_000);
        assertThat(store.automationTokens(today.plusDays(1))).isZero();
        store.settleAutomationTokens(today, 200_000, 100);
        assertThat(restarted.run(spec, Map.of()).ok()).isTrue();
        assertThat(restarted.tokensToday()).isGreaterThanOrEqualTo(100).isLessThan(200_000);
        assertThat(provider.chats).hasSize(1);
    }

    @Test void avisosIguaisAgrupamEPersistemSemHost() throws Exception {
        try (NotificationCenter center = new NotificationCenter(home.resolve("notifications.db"), clock, message -> { })) {
            AutomationNotifier notifier = new AutomationNotifier(center, (t, b, s) -> { throw new IllegalStateException("offline"); }, clock);
            AutomationSpec spec = AutomationSpec.fromMap(raw("api", List.of(notifyStep("tell", "Falhou"))));
            notifier.notify(spec, "Falhou", "API indisponível", "warning");
            notifier.notify(spec, "Falhou", "API indisponível", "warning");
            assertThat(center.pending()).singleElement().satisfies(message ->
                    assertThat(message.get("title")).isEqualTo("Falhou (2× desde 12:00)"));
        }
        try (NotificationCenter reopened = new NotificationCenter(home.resolve("notifications.db"), clock, message -> { })) {
            assertThat(reopened.pending()).hasSize(1);
        }
    }
}
