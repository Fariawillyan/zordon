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
package zordon.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.event.EventType;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.ZPath;
import zordon.api.trace.AcceptanceCriteria;
import zordon.core.chat.Intent;
import zordon.core.chat.IntentRouter;
import zordon.core.event.ZordonEventBus;
import zordon.core.zwp.ClientRequests;
import zordon.security.CommandValidator;
import zordon.security.DefaultPermissionEngine;
import zordon.security.Gatekeeper;
import zordon.security.PathPolicy;
import zordon.security.PermissionEngine;
import zordon.security.ProcessRunner;
import zordon.security.Redactor;
import zordon.security.SqliteAuditLog;

/** Ferramentas pelo caminho mediado (SPEC-016). */
class ToolsTest {

    @TempDir
    Path home;

    /** Um host falso: catálogo fixo e registro do que mandaram abrir. */
    static final class Host implements ClientRequests {
        final List<String> opened = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<Map<String, Object>> request(String sessionId, String method,
                Map<String, Object> params, Duration timeout) {
            return CompletableFuture.completedFuture(switch (method) {
                case "windows.apps" -> Map.of("apps", List.of(
                        Map.of("id", "a1", "name", "IntelliJ IDEA Community Edition"),
                        Map.of("id", "a2", "name", "Bloco de Notas")));
                case "windows.openApp" -> {
                    opened.add(String.valueOf(params.get("id")));
                    yield Map.of("opened", true);
                }
                default -> Map.of();
            });
        }
    }

    private SqliteAuditLog audit;
    private ZordonEventBus bus;
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private volatile PermissionEngine.Approval answer = PermissionEngine.Approval.DENY;
    private final Host host = new Host();
    private SkillRuntime runtime;
    private WindowsBridge windows;
    private zordon.security.vault.QuarantineVault vault;

    @BeforeEach
    void setUp() throws Exception {
        audit = new SqliteAuditLog(home.resolve("state/audit.db"), new Redactor(), Clock.systemUTC());
        bus = new ZordonEventBus(zordon.core.StartId.generate());
        PathPolicy policy = PathPolicy.defaults(home.toString(), List.of("~/dev"), List.of("~"));
        CommandValidator validator = new CommandValidator(Map.of("echo", "/bin/echo"));
        PermissionEngine.Approver approver = (action, actor, risk, ttl, perAction) -> {
            asked.add(action.tool() + " " + actor.origin().wire() + " " + perAction);
            return CompletableFuture.completedFuture(answer);
        };
        Gatekeeper gatekeeper = new Gatekeeper(
                new DefaultPermissionEngine(policy, validator, new Redactor(), () -> approver), audit);
        windows = new WindowsBridge(host);
        vault = new zordon.security.vault.QuarantineVault(home.resolve("quarentena"), Clock.systemUTC());
        ZPath base = ZPath.ofWsl(home.toString());
        runtime = new SkillRuntime(gatekeeper, bus, () -> false)
                .register(windows.openTool())
                .register(FileTools.list(policy, base))
                .register(FileTools.read(policy, base))
                .register(FileTools.write(policy, base))
                .register(ProcessTools.metrics())
                .register(ProcessTools.gitStatus(policy, base, new ProcessRunner(validator)))
                .register(QuarantineTools.quarantine(policy, base, vault))
                .register(QuarantineTools.restore(policy, vault));
        Files.createDirectories(home.resolve("dev/app"));
    }

    @AfterEach
    void tearDown() {
        audit.close();
        bus.close();
    }

    private String say(String text, RequestOrigin origin) throws Exception {
        Intent intent = new IntentRouter().route(text);
        return switch (intent) {
            case Intent.Tool tool -> runtime.invoke(tool.tool(), tool.args(), Principal.user(origin), "t1")
                    .get(10, TimeUnit.SECONDS).text();
            case Intent.Immediate immediate -> immediate.answer();
            default -> "modelo";
        };
    }

    @AcceptanceCriteria("SPEC-016/CA-3")
    @Test
    void abraOIntellijAbreOAtalhoDoCatalogo() throws Exception {
        assertThat(say("Zordon, abra o IntelliJ", RequestOrigin.VOICE))
                .as("sem host").isEqualTo("O host do Windows não está conectado; não consigo abrir aplicativos.");

        windows.hostConnected("h1");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (windows.catalog().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(say("Zordon, abra o IntelliJ", RequestOrigin.VOICE))
                .isEqualTo("Abrindo IntelliJ IDEA Community Edition.");
        assertThat(host.opened).containsExactly("a1");
        assertThat(say("abre o photoshop", RequestOrigin.UI))
                .isEqualTo("Não encontrei um aplicativo chamado photoshop.");
        assertThat(audit.verify(100).ok()).isTrue();
    }

    @AcceptanceCriteria("SPEC-016/CA-4")
    @Test
    void apagueOsLogsNaoExecutaNadaEOfereceAQuarentena() throws Exception {
        long before = audit.entries();
        String answer = say("Zordon, apague os logs do projeto", RequestOrigin.VOICE);
        assertThat(answer).isEqualTo(IntentRouter.NO_DELETE).contains("quarentena");
        assertThat(say("delete tudo em ~/dev", RequestOrigin.UI)).isEqualTo(IntentRouter.NO_DELETE);
        assertThat(audit.entries()).as("nada foi sequer tentado").isEqualTo(before);
    }

    @AcceptanceCriteria("SPEC-016/CA-5")
    @Test
    void escritaPorVozPedeNaTelaESemElaNadaEEscrito() throws Exception {
        Map<String, Object> args = Map.of("path", "~/dev/app/nota.txt", "content", "olá");

        answer = PermissionEngine.Approval.DENY;
        String denied = runtime.invoke("fs.write", args, Principal.user(RequestOrigin.VOICE), "t1")
                .get(10, TimeUnit.SECONDS).text();
        assertThat(asked).containsExactly("fs.write voice true");
        assertThat(denied).startsWith("Não fiz: negado pelo usuário");
        assertThat(home.resolve("dev/app/nota.txt")).doesNotExist();

        answer = PermissionEngine.Approval.ONCE;
        assertThat(runtime.invoke("fs.write", args, Principal.user(RequestOrigin.VOICE), "t2")
                .get(10, TimeUnit.SECONDS).text()).startsWith("Criei");
        assertThat(Files.readString(home.resolve("dev/app/nota.txt"))).isEqualTo("olá");

        assertThat(runtime.invoke("fs.write", args, Principal.user(RequestOrigin.UI), "t3")
                .get(10, TimeUnit.SECONDS).text()).as("não sobrescreve").contains("já existe");
        assertThat(Files.readString(home.resolve("dev/app/nota.txt"))).isEqualTo("olá");

        assertThat(runtime.invoke("fs.read", Map.of("path", "~/dev/app/nota.txt"), Principal.user(RequestOrigin.UI),
                "t4").get(10, TimeUnit.SECONDS).text()).isEqualTo("Li 1 linhas.");
        assertThat(runtime.invoke("fs.read", Map.of("path", "~/.ssh/id_ed25519"), Principal.user(RequestOrigin.UI),
                "t5").get(10, TimeUnit.SECONDS).text()).startsWith("Não fiz: caminho proibido");
    }

    @AcceptanceCriteria("SPEC-016/CA-6")
    @Test
    void listaDeFerramentasComRiscoEEfeitosESemExclusao() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        bus.subscribe("teste", java.util.Set.of(zordon.api.event.Topic.TOOLS),
                zordon.core.event.QueuePolicy.dropOldest(64), event -> {
            if (event.type() == EventType.TOOL_CALLED || event.type() == EventType.TOOL_RESULT) {
                events.add(event.type() + " " + event.payload().get("tool"));
            }
        });
        assertThat(runtime.list()).extracting(tool -> tool.get("name"))
                .containsExactly("app.open", "fs.list", "fs.quarantine", "fs.read", "fs.restore", "fs.write",
                        "git.status", "system.metrics");
        assertThat(runtime.list()).allSatisfy(tool -> {
            assertThat(tool).containsKeys("description", "risk", "effects");
            assertThat(tool.get("effects").toString().toLowerCase()).doesNotContain("delete");
        });
        assertThat(runtime.invoke("system.metrics", Map.of(), Principal.user(RequestOrigin.VOICE), "t1")
                .get(10, TimeUnit.SECONDS).text()).startsWith("Carga ");
        assertThat(runtime.invoke("fs.list", Map.of("path", "~/dev"), Principal.user(RequestOrigin.UI), "t2")
                .get(10, TimeUnit.SECONDS).text()).isEqualTo("1 item: app/.");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (events.size() < 4 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(events).contains("TOOL_CALLED system.metrics", "TOOL_RESULT system.metrics",
                "TOOL_CALLED fs.list", "TOOL_RESULT fs.list");
    }

    @AcceptanceCriteria("SPEC-017/CA-3")
    @Test
    void quarentenaMostraOsAlvosEOTotalESemAutorizacaoNadaSai() throws Exception {
        Path logs = Files.createDirectories(home.resolve("dev/app/logs"));
        for (int i = 0; i < 43; i++) {
            Files.writeString(logs.resolve("app-" + i + ".log"), "linha " + i);
        }
        List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
        PermissionEngine.Approver watching = (action, actor, risk, ttl, perAction) -> {
            seen.add(Map.of("summary", action.humanSummary(), "targets", action.touchedPaths().size(),
                    "count", action.targets(), "risk", risk.wire(), "perAction", perAction));
            return CompletableFuture.completedFuture(answer);
        };
        PathPolicy policy = PathPolicy.defaults(home.toString(), List.of("~/dev"), List.of("~"));
        SkillRuntime quarantine = new SkillRuntime(new Gatekeeper(new DefaultPermissionEngine(policy,
                new CommandValidator(Map.of()), new Redactor(), () -> watching), audit), bus, () -> false)
                .register(QuarantineTools.quarantine(policy, ZPath.ofWsl(home.toString()), vault));

        answer = PermissionEngine.Approval.DENY;
        String denied = quarantine.invoke("fs.quarantine", Map.of("path", "~/dev/app/logs"),
                Principal.user(RequestOrigin.VOICE), "t1").get(10, TimeUnit.SECONDS).text();
        assertThat(seen).singleElement().satisfies(request -> assertThat(request)
                .containsEntry("summary", "Mover 43 arquivos de " + logs + " para a quarentena (reversível)")
                .containsEntry("count", 43).containsEntry("targets", 43).containsEntry("risk", "red")
                .containsEntry("perAction", true));
        assertThat(denied).startsWith("Não fiz");
        try (var left = Files.list(logs)) {
            assertThat(left.count()).isEqualTo(43);
        }

        answer = PermissionEngine.Approval.ONCE;
        String done = quarantine.invoke("fs.quarantine", Map.of("path", "~/dev/app/logs"),
                Principal.user(RequestOrigin.UI), "t2").get(10, TimeUnit.SECONDS).text();
        assertThat(done).startsWith("Movi 43 arquivos para a quarentena. Para desfazer: restaurar q-");
        try (var left = Files.list(logs)) {
            assertThat(left.count()).isZero();
        }
    }

    @AcceptanceCriteria("SPEC-017/CA-4")
    @Test
    void movaParaAQuarentenaViraAFerramentaComOCaminhoOriginal() {
        assertThat(new IntentRouter().route("Zordon, mova para a quarentena ~/Dev/App/Logs"))
                .isEqualTo(new Intent.Tool("fs.quarantine", Map.of("path", "~/Dev/App/Logs"), "quarentena"));
        assertThat(new IntentRouter().route("coloque em quarentena D:\\Projeto\\logs."))
                .isEqualTo(new Intent.Tool("fs.quarantine", Map.of("path", "D:\\Projeto\\logs"), "quarentena"));
    }

    @AcceptanceCriteria("SPEC-017/CA-5")
    @Test
    void restaurarPelaFerramentaDevolveEAListaMostraOEstado() throws Exception {
        Path logs = Files.createDirectories(home.resolve("dev/app/tmp"));
        Files.writeString(logs.resolve("x.txt"), "x");
        answer = PermissionEngine.Approval.ONCE;
        String stored = runtime.invoke("fs.quarantine", Map.of("path", "~/dev/app/tmp"), Principal.user(RequestOrigin.UI),
                "t1").get(10, TimeUnit.SECONDS).text();
        String vaultId = stored.replaceAll(".*restaurar (q-[^.]+)\\..*", "$1");
        assertThat(vault.list()).singleElement().satisfies(item -> {
            assertThat(item.files()).isEqualTo(1);
            assertThat(item.bytes()).isEqualTo(1);
            assertThat(item.reason()).isEqualTo("pedido do usuário");
            assertThat(item.restored()).isFalse();
        });
        assertThat(runtime.invoke("fs.restore", Map.of("vaultId", vaultId), Principal.user(RequestOrigin.UI), "t2")
                .get(10, TimeUnit.SECONDS).text()).isEqualTo("Restaurei 1 arquivos.");
        assertThat(Files.readString(logs.resolve("x.txt"))).isEqualTo("x");
        assertThat(vault.list().getFirst().restored()).isTrue();
    }

    @AcceptanceCriteria("SPEC-019/CA-4")
    @Test
    void depoisDeUmaLeituraNoTurnoEnviarDadosViraRed() throws Exception {
        Files.writeString(home.resolve("dev/app/segredo.txt"), "conteúdo");
        Tool upload = new Tool() {
            @Override public String name() { return "net.send"; }
            @Override public String description() { return "envia dados"; }
            @Override public zordon.api.security.RiskLevel baseRisk() { return zordon.api.security.RiskLevel.YELLOW; }
            @Override public java.util.Set<zordon.api.security.Effect> effects() {
                return java.util.Set.of(zordon.api.security.Effect.NETWORK);
            }
            @Override public zordon.api.security.ActionDescriptor describe(Map<String, Object> args) {
                return new zordon.api.security.ActionDescriptor(name(), args, baseRisk(), effects(), List.of(), 1,
                        List.of(), "Enviar dados para fora");
            }
            @Override public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) {
                return ToolResult.of("enviado");
            }
        };
        runtime.register(upload);
        List<String> risks = new CopyOnWriteArrayList<>();
        bus.subscribe("risco", java.util.Set.of(zordon.api.event.Topic.TOOLS), zordon.core.event.QueuePolicy.dropOldest(64),
                event -> {
                    if (event.type() == EventType.TOOL_CALLED) {
                        risks.add(event.payload().get("tool") + " " + event.payload().get("risk"));
                    }
                });
        answer = PermissionEngine.Approval.ONCE;
        runtime.invoke("net.send", Map.of(), Principal.user(RequestOrigin.UI), "limpo").get(10, TimeUnit.SECONDS);
        runtime.invoke("fs.read", Map.of("path", "~/dev/app/segredo.txt"), Principal.user(RequestOrigin.UI), "sujo")
                .get(10, TimeUnit.SECONDS);
        assertThat(runtime.tainted("sujo")).isTrue();
        runtime.invoke("net.send", Map.of(), Principal.user(RequestOrigin.UI), "sujo").get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (risks.size() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(risks).containsExactly("net.send yellow", "fs.read green", "net.send red");
        runtime.endTurn("sujo");
        assertThat(runtime.tainted("sujo")).isFalse();
    }
}
