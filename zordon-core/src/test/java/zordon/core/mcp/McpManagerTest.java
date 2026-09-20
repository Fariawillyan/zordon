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
package zordon.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;
import zordon.api.security.Severity;
import zordon.api.trace.AcceptanceCriteria;
import zordon.core.event.ZordonEventBus;
import zordon.core.notify.NotificationCenter;
import zordon.core.notify.ZordonMessage;
import zordon.core.tools.SkillRuntime;
import zordon.security.CommandValidator;
import zordon.security.DefaultPermissionEngine;
import zordon.security.Gatekeeper;
import zordon.security.PathPolicy;
import zordon.security.PermissionEngine;
import zordon.security.ProcessRunner;
import zordon.security.Redactor;
import zordon.security.SqliteAuditLog;

/** O cliente MCP contra um servidor falso de verdade, por stdio (SPEC-020). */
class McpManagerTest {

    @TempDir
    Path home;

    private SqliteAuditLog audit;
    private ZordonEventBus bus;
    private NotificationCenter notifications;
    private final List<ZordonMessage> delivered = new CopyOnWriteArrayList<>();
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final AtomicLong nanos = new AtomicLong(1);
    private Gatekeeper gatekeeper;
    private ProcessRunner runner;
    private SkillRuntime runtime;
    private final List<McpManager> managers = new ArrayList<>();
    private Path config;
    private Path starts;
    private Path script;

    @BeforeEach
    void setUp() throws Exception {
        audit = new SqliteAuditLog(home.resolve("state/audit.db"), new Redactor(), Clock.systemUTC());
        bus = new ZordonEventBus(zordon.core.StartId.generate());
        notifications = new NotificationCenter(home.resolve("state/notifications.db"), Clock.systemUTC(),
                delivered::add);
        PathPolicy policy = PathPolicy.defaults(home.toString(), List.of("~/dev"), List.of("~"));
        CommandValidator validator = new CommandValidator(Map.of("python3", "/usr/bin/python3"));
        PermissionEngine.Approver approver = (action, actor, risk, ttl, perAction) -> {
            asked.add(action.tool() + " " + risk.wire());
            return CompletableFuture.completedFuture(PermissionEngine.Approval.DENY);
        };
        gatekeeper = new Gatekeeper(new DefaultPermissionEngine(policy, validator, new Redactor(), () -> approver),
                audit);
        runner = new ProcessRunner(validator);
        runtime = new SkillRuntime(gatekeeper, bus, () -> false);
        script = Path.of(getClass().getResource("/mcp/fake_mcp.py").toURI());
        config = home.resolve("fake.json");
        starts = home.resolve("starts.txt");
        variant(1);
    }

    @AfterEach
    void tearDown() {
        managers.forEach(McpManager::close);
        notifications.close();
        audit.close();
        bus.close();
    }

    private void variant(int variant) throws Exception {
        Files.writeString(config, "{\"variant\": " + variant + ", \"starts\": \"" + starts + "\"}");
    }

    private McpManager manager(String program, RiskLevel floor) {
        McpManager manager = new McpManager(
                List.of(new McpManager.Server("fake", List.of(program, script.toString(), config.toString()), floor,
                        true)),
                gatekeeper, runner, runtime, notifications, home.resolve("state"), home.resolve("mcp-work"),
                Clock.systemUTC(), nanos::get)
                .timings(Duration.ofMillis(800), List.of(Duration.ofMillis(200)));
        managers.add(manager);
        return manager;
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("não aconteceu: " + what);
    }

    private List<String> mcpTools() {
        return runtime.list().stream().map(row -> String.valueOf(row.get("name")))
                .filter(name -> name.startsWith("mcp.")).toList();
    }

    private String risk(String tool) {
        return runtime.list().stream().filter(row -> tool.equals(row.get("name")))
                .map(row -> String.valueOf(row.get("risk"))).findFirst().orElseThrow();
    }

    private String call(String tool, Map<String, Object> args) throws Exception {
        return runtime.invoke(tool, args, Principal.user(RequestOrigin.UI), "t1").get(20, TimeUnit.SECONDS).text();
    }

    /** Ferramentas já vistas há mais de uma semana: o risco é só piso e anotações. */
    private void seenLongAgo() throws Exception {
        String old = Instant.now().minus(Duration.ofDays(30)).toString();
        StringBuilder seen = new StringBuilder("{");
        for (String server : List.of("fake", "strict")) {
            for (String tool : List.of("echo", "wipe", "plain", "hang", "fail", "quit")) {
                seen.append(seen.length() > 1 ? "," : "").append('"').append(server).append('.').append(tool)
                        .append("\":\"").append(old).append('"');
            }
        }
        Files.createDirectories(home.resolve("state"));
        Files.writeString(home.resolve("state/mcp-seen.json"), seen.append('}'));
    }

    @AcceptanceCriteria("SPEC-020/CA-1")
    @Test
    void servidorDeclaradoConectaPeloCaminhoMediadoSemAtrasarONucleo() throws Exception {
        seenLongAgo();
        McpManager manager = manager("python3", RiskLevel.GREEN);
        long before = System.nanoTime();
        manager.start();
        assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofMillis(200));

        await(() -> manager.state("fake") == McpManager.State.CONNECTED, "conectar");

        assertThat(mcpTools()).containsExactly("mcp.fake.echo", "mcp.fake.fail", "mcp.fake.hang", "mcp.fake.plain",
                "mcp.fake.quit", "mcp.fake.wipe");
        assertThat(call("mcp.fake.echo", Map.of("text", "oi"))).isEqualTo("eco: oi");
        assertThat(audit.verify(100).ok()).isTrue();
        List<String> audited = new ArrayList<>();
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + home.resolve("state/audit.db"));
                var s = db.createStatement();
                var r = s.executeQuery("SELECT tool FROM audit ORDER BY id")) {
            while (r.next()) {
                audited.add(r.getString(1));
            }
        }
        assertThat(audited).contains("mcp.start", "mcp.fake.echo");
        assertThat(manager.describe()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("state", "connected").containsEntry("drift", false);
        });
    }

    @AcceptanceCriteria("SPEC-020/CA-2")
    @Test
    void oPisoEhNossoEAsAnotacoesSoSobem() throws Exception {
        seenLongAgo();
        McpManager manager = manager("python3", RiskLevel.GREEN);
        manager.start();
        await(() -> manager.state("fake") == McpManager.State.CONNECTED, "conectar");

        assertThat(risk("mcp.fake.echo")).as("leitura declarada, sem rede").isEqualTo("green");
        assertThat(risk("mcp.fake.plain")).as("sem anotação: pode modificar").isEqualTo("yellow");
        assertThat(risk("mcp.fake.wipe")).as("destrutiva").isEqualTo("red");

        assertThat(call("mcp.fake.wipe", Map.of())).startsWith("Não fiz");
        assertThat(asked).contains("mcp.fake.wipe red");

        McpManager strict = new McpManager(List.of(new McpManager.Server("strict",
                List.of("python3", script.toString(), config.toString()), RiskLevel.YELLOW, true)), gatekeeper,
                runner, runtime, notifications, home.resolve("state"), home.resolve("mcp-work"), Clock.systemUTC(),
                nanos::get);
        managers.add(strict);
        strict.start();
        await(() -> strict.state("strict") == McpManager.State.CONNECTED, "conectar o segundo");
        assertThat(risk("mcp.strict.echo")).as("anotação de leitura não desce abaixo do piso").isEqualTo("yellow");
    }

    @AcceptanceCriteria("SPEC-020/CA-2")
    @Test
    void ferramentaNovaSobeUmNivelNaPrimeiraSemana() throws Exception {
        McpManager manager = manager("python3", RiskLevel.GREEN);
        manager.start();
        await(() -> manager.state("fake") == McpManager.State.CONNECTED, "conectar");

        assertThat(risk("mcp.fake.echo")).isEqualTo("yellow");
        assertThat(Files.readString(home.resolve("state/mcp-seen.json"))).contains("fake.echo");
    }

    @AcceptanceCriteria("SPEC-020/CA-3")
    @Test
    void superficieDiferenteBloqueiaAteAprovarNaTela() throws Exception {
        McpManager first = manager("python3", RiskLevel.GREEN);
        first.start();
        await(() -> first.state("fake") == McpManager.State.CONNECTED, "primeira conexão");
        first.close();
        await(() -> mcpTools().isEmpty(), "ferramentas saírem com o encerramento");

        variant(2);
        McpManager second = manager("python3", RiskLevel.GREEN);
        second.start();
        await(() -> second.state("fake") == McpManager.State.DRIFT, "detectar a mudança");

        assertThat(mcpTools()).isEmpty();
        assertThat(delivered).anySatisfy(message -> {
            assertThat(message.severity()).isEqualTo(Severity.HIGH);
            assertThat(message.title()).contains("fake");
        });
        assertThat(second.describe().getFirst()).containsEntry("drift", true);

        assertThat(second.approve("fake")).isTrue();
        assertThat(second.state("fake")).isEqualTo(McpManager.State.CONNECTED);
        assertThat(mcpTools()).contains("mcp.fake.echo");
        assertThat(second.approve("fake")).as("nada mais pendente").isFalse();
    }

    @AcceptanceCriteria("SPEC-020/CA-4")
    @Test
    void chamadaQueTravaTerminaNoPrazoEFalhasSeguidasAbremODisjuntor() throws Exception {
        seenLongAgo();
        McpManager manager = manager("python3", RiskLevel.GREEN);
        manager.start();
        await(() -> manager.state("fake") == McpManager.State.CONNECTED, "conectar");

        long started = System.nanoTime();
        assertThat(call("mcp.fake.hang", Map.of())).contains("não respondeu");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @AcceptanceCriteria("SPEC-020/CA-4")
    @Test
    void cincoFalhasEmUmMinutoAbremODisjuntorPorCincoMinutos() throws Exception {
        seenLongAgo();
        McpManager manager = manager("python3", RiskLevel.GREEN);
        manager.start();
        await(() -> manager.state("fake") == McpManager.State.CONNECTED, "conectar");

        for (int i = 0; i < 5; i++) {
            assertThat(call("mcp.fake.fail", Map.of())).contains("erro");
        }
        assertThat(call("mcp.fake.echo", Map.of("text", "x"))).contains("em pausa");

        nanos.addAndGet(Duration.ofMinutes(5).plusSeconds(1).toNanos());
        assertThat(call("mcp.fake.echo", Map.of("text", "x"))).isEqualTo("eco: x");
    }

    @AcceptanceCriteria("SPEC-020/CA-5")
    @Test
    void quedaTiraAsFerramentasEReconectaComEspera() throws Exception {
        seenLongAgo();
        McpManager manager = manager("python3", RiskLevel.GREEN);
        manager.start();
        await(() -> manager.state("fake") == McpManager.State.CONNECTED, "conectar");

        call("mcp.fake.quit", Map.of());
        await(() -> Files.exists(starts) && lines(starts) == 2 && manager.state("fake") == McpManager.State.CONNECTED,
                "reconectar");
        assertThat(mcpTools()).contains("mcp.fake.echo");
        assertThat(call("mcp.fake.echo", Map.of("text", "de volta"))).isEqualTo("eco: de volta");
    }

    @Test
    void programaForaDoCatalogoFalhaSemTentarDeNovo() throws Exception {
        McpManager manager = manager("/opt/nada/servidor-mcp", RiskLevel.GREEN);
        manager.start();
        await(() -> manager.state("fake") == McpManager.State.FAILED, "falhar");
        Thread.sleep(500);
        assertThat(manager.state("fake")).isEqualTo(McpManager.State.FAILED);
        assertThat(manager.describe().getFirst().get("error")).asString().contains("negado");
        assertThat(asked).contains("mcp.start red");
    }

    @Test
    void configuracaoLidaDoToml() throws Exception {
        Path toml = home.resolve("config.toml");
        Files.writeString(toml, """
                [[mcp.server]]
                name = "docker"
                command = ["docker", "run", "-i", "--rm", "mcp/docker"]
                risk_floor = "green"

                [[mcp.server]]
                name = "Inválido!"
                command = ["x"]

                [[mcp.server]]
                name = "git"
                command = ["uvx", "mcp-server-git"]
                autostart = false
                """, StandardCharsets.UTF_8);

        assertThat(McpManager.load(toml)).containsExactly(
                new McpManager.Server("docker", List.of("docker", "run", "-i", "--rm", "mcp/docker"), RiskLevel.GREEN,
                        true),
                new McpManager.Server("git", List.of("uvx", "mcp-server-git"), RiskLevel.YELLOW, false));
        assertThat(McpManager.load(home.resolve("nao-existe.toml"))).isEmpty();
    }

    @Test
    void oBlocoDoExemploDescomentadoEhValido() throws Exception {
        Path example = Path.of(System.getProperty("user.dir")).getParent().resolve("packaging/wsl/config.toml.example");
        String uncommented = String.join("\n", Files.readAllLines(example).stream()
                .map(line -> line.matches("^# (\\[|[a-z_]+ +=).*") ? line.substring(2) : line).toList());

        assertThat(McpManager.load(Files.writeString(home.resolve("exemplo.toml"), uncommented)))
                .extracting(McpManager.Server::name, McpManager.Server::floor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("git", RiskLevel.YELLOW));
    }

    private static long lines(Path file) {
        try {
            return Files.readAllLines(file).size();
        } catch (java.io.IOException e) {
            return 0;
        }
    }
}
