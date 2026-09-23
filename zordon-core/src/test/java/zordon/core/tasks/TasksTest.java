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
package zordon.core.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.ai.ModelPolicy;
import zordon.ai.registry.ProviderRegistry;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.ZPath;
import zordon.api.trace.AcceptanceCriteria;
import zordon.core.activity.ActivityInterpreter;
import zordon.core.agents.AgentRegistry;
import zordon.core.agents.AgentRunner;
import zordon.core.agents.TurnScopes;
import zordon.core.chat.Intent;
import zordon.core.chat.IntentRouter;
import zordon.core.chat.PromptComposer;
import zordon.core.chat.ToolLoopTestSupport;
import zordon.core.event.QueuePolicy;
import zordon.core.event.ZordonEventBus;
import zordon.core.tools.FileTools;
import zordon.core.tools.ModelToolCaller;
import zordon.core.tools.ProcessTools;
import zordon.core.tools.SkillRuntime;
import zordon.memory.SqliteMemoryStore;
import zordon.memory.ZordonDatabase;
import zordon.memory.TaskStore;
import zordon.security.CommandValidator;
import zordon.security.DefaultPermissionEngine;
import zordon.security.Gatekeeper;
import zordon.security.PathPolicy;
import zordon.security.PermissionEngine;
import zordon.security.ProcessRunner;
import zordon.security.Redactor;
import zordon.security.SqliteAuditLog;

/** Planos duráveis e conclusão verificada (SPEC-023). */
class TasksTest {

    @TempDir
    Path home;

    private final List<EventEnvelope> events = new CopyOnWriteArrayList<>();
    private final AtomicLong nanos = new AtomicLong(1);
    private ZordonDatabase db;
    private SqliteMemoryStore store;
    private SqliteAuditLog audit;
    private ZordonEventBus bus;
    private SkillRuntime runtime;
    private AgentRegistry registry;
    private ToolLoopTestSupport.Scripted provider;
    private TaskRunner runner;
    private Planner planner;

    @BeforeEach
    void setUp() throws Exception {
        db = new ZordonDatabase(home.resolve("zordon.db"), Clock.systemUTC());
        store = db.memory();
        audit = new SqliteAuditLog(home.resolve("audit.db"), new Redactor(), Clock.systemUTC());
        bus = new ZordonEventBus("01TESTE00000000000000000000");
        bus.subscribe("teste", Set.of(Topic.AGENTS), QueuePolicy.dropOldest(1024), events::add);
        Path docker = home.resolve("bin/docker");
        Files.createDirectories(docker.getParent());
        Files.writeString(docker, "#!/bin/sh\nprintf 'api\\tUp 3 minutes\\tminha/api:1\\n'\n");
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"));
        PathPolicy policy = PathPolicy.defaults(home.toString(), List.of("~/dev"), List.of("~"));
        CommandValidator validator = new CommandValidator(Map.of("docker", docker.toString()));
        PermissionEngine.Approver deny = (action, actor, risk, ttl, perAction) ->
                CompletableFuture.completedFuture(PermissionEngine.Approval.DENY);
        Gatekeeper gatekeeper = new Gatekeeper(new DefaultPermissionEngine(policy, validator, new Redactor(),
                () -> deny), audit);
        ZPath base = ZPath.ofWsl(home.toString());
        runtime = new SkillRuntime(gatekeeper, bus, () -> false)
                .register(FileTools.read(policy, base))
                .register(FileTools.write(policy, base))
                .register(ProcessTools.metrics())
                .register(ProcessTools.dockerPs(base, new ProcessRunner(validator)));
        registry = new AgentRegistry(home.resolve("agents"));
        provider = new ToolLoopTestSupport.Scripted();
        ProviderRegistry providers = ProviderRegistry.of(Map.of(ModelPolicy.DEFAULT_PROVIDER, provider),
                ModelPolicy.defaults());
        AgentRunner agents = new AgentRunner(providers, new PromptComposer(),
                new ModelToolCaller(runtime, new TurnScopes()), bus);
        planner = new Planner(providers, registry, () -> Set.of("docker.ps", "system.metrics", "fs.read"));
        runner = new TaskRunner(db.tasks(), planner, new Verifier(providers, runtime), registry, agents, bus, nanos::get);
        runtime.register(TaskTools.create(runner));
    }

    @AfterEach
    void tearDown() {
        db.close();
        audit.close();
        bus.close();
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("não aconteceu: " + what);
    }

    private String state(String taskId) {
        return db.tasks().task(taskId).orElseThrow().state();
    }

    private List<String> stepStates(String taskId) {
        return db.tasks().task(taskId).orElseThrow().steps().stream().map(TaskStore.StepView::state).toList();
    }

    private List<String> taskEvents(String taskId) {
        return events.stream().filter(event -> event.type() == EventType.TASK_STATE
                        && taskId.equals(event.payload().get("taskId")))
                .map(event -> (event.payload().get("stepId") == null ? "" : event.payload().get("stepId") + ":")
                        + event.payload().get("state"))
                .toList();
    }

    @AcceptanceCriteria("SPEC-023/CA-1")
    @Test
    void planeJeEFacaViraPlanoGravadoEComunicadoAntesDeComecar() throws Exception {
        assertThat(new IntentRouter().route("Zordon, planeje e faça: veja se a API está de pé e me avise."))
                .isEqualTo(new Intent.Tool("task.create", Map.of("goal", "veja se a API está de pé e me avise"),
                        "planejar"));
        provider.thenChat("""
                {"steps": [
                  {"id": "s1", "title": "Ver os containers", "agent": "system", "dependsOn": [], "risk": "green",
                   "doneWhen": {"type": "tool", "tool": "docker.ps", "args": {}, "expect": "api"}},
                  {"id": "s2", "title": "Avisar o resultado", "agent": "zordon", "dependsOn": ["s1"], "risk": "green",
                   "doneWhen": {"type": "human", "criterion": "o usuário leu"}}]}""")
                .thenChat("A API está de pé.")
                .thenChat("Avisado: a API está de pé.");

        String answer = runtime.invoke("task.create", Map.of("goal", "veja se a API está de pé e me avise"),
                Principal.user(RequestOrigin.VOICE), "t1").get(15, TimeUnit.SECONDS).text();

        assertThat(answer).isEqualTo("Plano com 2 etapas: Ver os containers; Avisar o resultado. Começando.");
        String taskId = db.tasks().tasks(1).getFirst().id();
        await(() -> "waiting_human".equals(state(taskId)), "esperar o usuário");
        assertThat(taskEvents(taskId)).startsWith("planned", "running", "s1:running")
                .contains("s1:done", "s2:running", "s2:waiting_human", "waiting_human");
        assertThat(provider.chats.getFirst().systemPrompt()).contains("Você é o Planner do Zordon")
                .contains("- research: ");
    }

    @AcceptanceCriteria("SPEC-023/CA-2")
    @Test
    void soContaComVereditoFerramentaJulgamentoEHumano() throws Exception {
        provider.thenChat("""
                {"steps": [
                  {"id": "s1", "title": "Conferir os containers", "agent": "system", "risk": "green",
                   "doneWhen": {"type": "tool", "tool": "docker.ps", "expect": "banco"}},
                  {"id": "s2", "title": "Explicar a arquitetura", "agent": "research", "risk": "green",
                   "doneWhen": {"type": "judgement", "criterion": "cita os três serviços"}},
                  {"id": "s3", "title": "Mandar o resumo", "agent": "zordon", "risk": "green",
                   "doneWhen": {"type": "human", "criterion": "resumo enviado"}}]}""")
                .thenChat("Olhei os containers.")
                .thenChat("A arquitetura tem API e banco.")
                .thenChat("{\"verdict\": \"fail\", \"reason\": \"só cita dois serviços\"}")
                .thenChat("Resumo pronto.");

        String taskId = runner.create("explique o sistema", RequestOrigin.UI).taskId();
        await(() -> !"running".equals(state(taskId)) && !"planned".equals(state(taskId)), "terminar");

        assertThat(stepStates(taskId)).containsExactly("failed", "failed", "waiting_human");
        assertThat(state(taskId)).isEqualTo("waiting_human");
        String verifierRequest = provider.chats.get(3).messages().getLast().text();
        assertThat(provider.chats.get(3).systemPrompt()).isEqualTo(Verifier.SYSTEM);
        assertThat(verifierRequest).contains("cita os três serviços")
                .contains("[Resposta de quem executou, sem evidência]");

        assertThat(runner.confirm(taskId, "s1", true)).as("s1 não espera o usuário").isEmpty();
        assertThat(runner.confirm(taskId, "s3", true)).contains("done");
        await(() -> "failed".equals(state(taskId)), "fechar como falha");
        assertThat(db.tasks().taskStats()).as("todo veredito fica, inclusive o que mandou para a tela")
                .containsEntry("verdicts7d", Map.of("fail", 2L, "inconclusive", 1L, "pass", 1L));
    }

    @AcceptanceCriteria("SPEC-023/CA-3")
    @Test
    void etapaQueFalhaBloqueiaAsDependentesEAsOutrasSeguem() throws Exception {
        provider.thenChat("""
                {"steps": [
                  {"id": "a", "title": "Ler o log", "agent": "research", "risk": "green",
                   "doneWhen": {"type": "judgement", "criterion": "achou o erro"}},
                  {"id": "b", "title": "Corrigir a causa", "agent": "developer", "dependsOn": ["a"], "risk": "yellow",
                   "doneWhen": {"type": "human", "criterion": "corrigido"}},
                  {"id": "c", "title": "Ver as métricas", "agent": "system", "risk": "green",
                   "doneWhen": {"type": "tool", "tool": "docker.ps", "expect": "api"}}]}""")
                .thenChat("Não achei nada.")
                .thenChat("{\"verdict\": \"fail\", \"reason\": \"sem erro nos logs\"}")
                .thenChat("As métricas estão normais.");

        String taskId = runner.create("descubra e corrija o erro", RequestOrigin.UI).taskId();
        await(() -> "failed".equals(state(taskId)), "terminar");

        assertThat(stepStates(taskId)).containsExactly("failed", "blocked", "done");
        assertThat(provider.chats).as("b nunca rodou").hasSize(4);
    }

    @AcceptanceCriteria("SPEC-023/CA-4")
    @Test
    void quedaNoMeioBloqueiaAvisaENaoRepeteSemRetomada() throws Exception {
        String taskId = db.tasks().createTask("rodar o build e publicar", "ui", List.of(
                new TaskStore.PlanStep("s1", "Rodar o build", "developer", List.of(), "yellow",
                        "{\"type\":\"human\",\"criterion\":\"build verde\"}")));
        db.tasks().taskState(taskId, "running", null);
        db.tasks().stepState(taskId, "s1", "running", null);

        List<TaskStore.TaskView> interrupted = runner.recover();

        assertThat(interrupted).extracting(TaskStore.TaskView::goal).containsExactly("rodar o build e publicar");
        assertThat(state(taskId)).isEqualTo("blocked");
        assertThat(stepStates(taskId)).containsExactly("blocked");
        assertThat(events).anySatisfy(event -> assertThat(event.payload()).containsEntry("reason", "o núcleo reiniciou"));
        Thread.sleep(200);
        assertThat(provider.chats).as("nada reexecutado sozinho").isEmpty();

        provider.thenChat("Build rodado.");
        assertThat(runner.resume(taskId)).isTrue();
        await(() -> "waiting_human".equals(state(taskId)), "retomar");
        assertThat(db.tasks().task(taskId).orElseThrow().steps().getFirst().attempts()).isEqualTo(2);
    }

    @Test
    void plannerRefazUmaVezERecusaCicloExcessoEFerramentaQueNaoConfere() throws Exception {
        provider.thenChat("""
                {"steps": [{"id": "a", "title": "A", "agent": "x", "dependsOn": ["b"]},
                           {"id": "b", "title": "B", "agent": "x", "dependsOn": ["a"]}]}""")
                .thenChat("""
                {"steps": [{"id": "a", "title": "A", "agent": "desconhecido", "risk": "yellow",
                            "doneWhen": {"type": "judgement", "criterion": "feito"}}]}""");

        List<TaskStore.PlanStep> plan = planner.plan("faça algo");

        assertThat(plan).singleElement().satisfies(step -> {
            assertThat(step.agent()).isEqualTo("zordon");
            assertThat(step.doneWhen()).as("julgamento em etapa com efeito vira humano").contains("\"human\"");
        });
        assertThat(provider.chats.get(1).messages().getLast().text()).contains("ciclo");

        StringBuilder eleven = new StringBuilder("{\"steps\": [");
        for (int i = 0; i < 11; i++) {
            eleven.append(i == 0 ? "" : ",").append("{\"id\": \"s").append(i).append("\", \"title\": \"T\"}");
        }
        assertThatThrownBy(() -> planner.validate(eleven + "]}")).hasMessageContaining("máximo é 10");
        assertThatThrownBy(() -> planner.validate("""
                {"steps": [{"id": "a", "title": "A", "doneWhen": {"type": "tool", "tool": "fs.write", "expect": "x"}}]}"""))
                .hasMessageContaining("fs.write");
        provider.thenChat("não sei").thenChat("{}");
        assertThatThrownBy(() -> planner.plan("outra coisa")).isInstanceOf(Planner.PlanException.class)
                .hasMessageContaining("duas vezes");
    }

    @Test
    void aVozDizOTituloDaEtapaEConcluidaSoNoFim() {
        ActivityInterpreter interpreter = new ActivityInterpreter();
        List<ActivityInterpreter.Output> running = interpreter.accept(new EventEnvelope(1, Instant.now(),
                EventType.TASK_STATE, Map.of("taskId", "t", "stepId", "s1", "state", "running",
                        "title", "Ver os containers")));
        List<ActivityInterpreter.Output> stepDone = interpreter.accept(new EventEnvelope(2, Instant.now(),
                EventType.TASK_STATE, Map.of("taskId", "t", "stepId", "s1", "state", "done", "title", "Ver")));
        List<ActivityInterpreter.Output> done = interpreter.accept(new EventEnvelope(3, Instant.now(),
                EventType.TASK_STATE, Map.of("taskId", "t", "state", "done")));
        List<ActivityInterpreter.Output> waiting = interpreter.accept(new EventEnvelope(4, Instant.now(),
                EventType.TASK_STATE, Map.of("taskId", "t", "state", "waiting_human")));

        assertThat(said(running)).containsExactly("Ver os containers");
        assertThat(said(stepDone)).as("etapa concluída não é 'tarefa concluída'").isEmpty();
        assertThat(said(done)).containsExactly("Tarefa concluída.");
        assertThat(said(waiting)).containsExactly("Preciso que você confira o resultado.");
    }

    private static List<String> said(List<ActivityInterpreter.Output> outputs) {
        return outputs.stream().filter(ActivityInterpreter.Say.class::isInstance)
                .map(output -> ((ActivityInterpreter.Say) output).narration().text()).toList();
    }
}
