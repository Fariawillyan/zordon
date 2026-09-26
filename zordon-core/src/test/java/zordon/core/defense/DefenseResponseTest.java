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
package zordon.core.defense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;
import zordon.api.security.ZPath;
import zordon.api.trace.AcceptanceCriteria;
import zordon.core.event.QueuePolicy;
import zordon.core.event.ZordonEventBus;
import zordon.core.notify.NotificationCenter;
import zordon.core.notify.ZordonMessage;
import zordon.core.tools.SkillRuntime;
import zordon.core.tools.Tool;
import zordon.core.tools.ToolResult;
import zordon.defense.CircuitBreakers;
import zordon.memory.SqliteMemoryStore;
import zordon.memory.ZordonDatabase;
import zordon.security.CommandValidator;
import zordon.security.Gatekeeper;
import zordon.security.PathPolicy;
import zordon.security.PermissionEngine;
import zordon.security.PermissionEngines;
import zordon.security.Redactor;
import zordon.security.SqliteAuditLog;

/** Os playbooks, o disjuntor e o histórico da defesa (SPEC-027). */
class DefenseResponseTest {

    @TempDir
    Path home;

    private final List<EventEnvelope> events = new CopyOnWriteArrayList<>();
    private final List<ZordonMessage> notices = new CopyOnWriteArrayList<>();
    private final List<String> isolated = new CopyOnWriteArrayList<>();
    private final List<String> cancelled = new CopyOnWriteArrayList<>();
    private final List<String> lockdowns = new CopyOnWriteArrayList<>();
    private ZordonDatabase db;
    private SqliteMemoryStore store;
    private SqliteAuditLog audit;
    private ZordonEventBus bus;
    private NotificationCenter notifications;
    private CircuitBreakers breakers;
    private DefenseService defense;
    private DefenseEngine response;
    private SkillRuntime runtime;

    @BeforeEach
    void setUp() {
        db = new ZordonDatabase(home.resolve("zordon.db"), Clock.systemUTC());
        store = db.memory();
        audit = new SqliteAuditLog(home.resolve("audit.db"), new Redactor(), Clock.systemUTC());
        bus = new ZordonEventBus("01TESTE00000000000000000000");
        bus.subscribe("teste", Set.of(Topic.SECURITY), QueuePolicy.dropOldest(256), events::add);
        notifications = new NotificationCenter(home.resolve("notifications.db"), Clock.systemUTC(), notices::add);
        defense = new DefenseService(new DefenseService.Outlets(db.findings(), notifications, bus), new Redactor(),
                Clock.systemUTC(), System::nanoTime);
        breakers = new CircuitBreakers(Clock.systemUTC());
        response = new DefenseEngine(breakers, db.securityEvents(), notifications, bus, new DefenseEngine.Actions() {
            @Override public boolean isolateMcp(String server) { return isolated.add(server); }
            @Override public boolean cancelAgent(String agent) { return cancelled.add(agent); }
            @Override public void lockdown(String reason) { lockdowns.add(reason); }
        }, Clock.systemUTC());
        defense.respondWith(response::respond);
        PermissionEngine.Approver allow = request ->
                CompletableFuture.completedFuture(PermissionEngine.Approval.ONCE);
        Gatekeeper gatekeeper = new Gatekeeper(PermissionEngines.standard(
                PathPolicy.defaults(home.toString(), List.of("~/dev"), List.of("~")), new CommandValidator(Map.of()),
                new Redactor(), () -> allow), audit);
        runtime = new SkillRuntime(gatekeeper, bus, () -> false).observedBy(defense)
                .breakerBy(actor -> breakers.isOpen("agent:" + actor.actor().replaceFirst("^agent:", "")));
    }

    @AfterEach
    void tearDown() {
        notifications.close();
        audit.close();
        bus.close();
        db.close();
    }

    private Tool violating() {
        return new Tool() {
            @Override public String name() { return "fs.read"; }
            @Override public String description() { return "Lê um arquivo."; }
            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(Effect.READ_FS); }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) {
                return new ActionDescriptor(name(), args, RiskLevel.GREEN, Set.of(Effect.READ_FS, Effect.NETWORK),
                        List.of(ZPath.ofWsl(home + "/dev/x.md")), 1, List.of(), "Ler");
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) {
                return ToolResult.of("ok");
            }
        };
    }

    @AcceptanceCriteria("SPEC-027/CA-1")
    @Test
    void efeitoNaoDeclaradoAbreODisjuntorEAProximaAcaoDoAgenteEhNegada() throws Exception {
        runtime.register(violating());
        Principal agent = new Principal("agent:developer", RequestOrigin.UI, false);

        String first = runtime.invoke("fs.read", Map.of("path", "~/dev/x.md"), agent, "t1").get(10, TimeUnit.SECONDS)
                .text();

        assertThat(first).isEqualTo("ok");
        assertThat(breakers.isOpen("agent:developer")).isTrue();
        assertThat(cancelled).containsExactly("developer");
        assertThat(events).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo(EventType.CIRCUIT_BREAKER_OPENED);
            assertThat(event.payload()).containsEntry("subject", "agent:developer");
        });
        assertThat(response.events(10)).singleElement().satisfies(stored -> {
            assertThat(stored).containsEntry("executed", "abrir o disjuntor").containsEntry("outcome", "CONTAINED");
            assertThat(stored.get("userMessageId")).isNotNull();
            assertThat(notices).anySatisfy(message ->
                    assertThat(message.id()).isEqualTo(stored.get("userMessageId")));
        });
        assertThat(db.securityEvents().securityChainOk()).isTrue();

        String second = runtime.invoke("fs.read", Map.of("path", "~/dev/x.md"), agent, "t2").get(10, TimeUnit.SECONDS)
                .text();
        assertThat(second).startsWith("Não fiz").contains("disjuntor aberto");

        assertThat(response.release("agent:developer", "supervised")).contains("half_open");
        assertThat(runtime.invoke("fs.read", Map.of("path", "~/dev/x.md"), agent, "t3").get(10, TimeUnit.SECONDS)
                .text()).as("liberado, volta a rodar").isEqualTo("ok");
    }

    @AcceptanceCriteria("SPEC-027/CA-2")
    @Test
    void driftDeMcpIsolaOServidorEOHistoricoNaoSeAltera() throws Exception {
        defense.mcpDrift("docker", "a superfície mudou");

        assertThat(isolated).containsExactly("docker");
        assertThat(breakers.isOpen("mcp:docker")).isTrue();
        assertThat(response.breakers()).singleElement().satisfies(breaker ->
                assertThat(breaker).containsEntry("subject", "mcp:docker").containsEntry("state", "open"));
        assertThat(response.events(10)).singleElement().satisfies(event ->
                assertThat(event).containsEntry("executed", "isolar o servidor MCP"));

        try (var db = DriverManager.getConnection("jdbc:sqlite:" + home.resolve("zordon.db"));
                var statement = db.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE security_event SET outcome = 'FAILED'"))
                    .hasMessageContaining("não se altera");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM security_event"))
                    .hasMessageContaining("não se apaga");
        }
    }

    @AcceptanceCriteria("SPEC-027/CA-3")
    @Test
    void integridadeQuebradaEntraEmLockdownEDetectorSemPlaybookSoObserva() {
        defense.auditChainBroken("cadeia quebrada a partir da linha 7");

        assertThat(lockdowns).singleElement().asString().contains("Integridade do Zordon");
        assertThat(response.events(10)).singleElement().satisfies(event ->
                assertThat(event).containsEntry("executed", "lockdown").containsEntry("outcome", "CONTAINED"));

        defense.observe(new zordon.defense.Observation("host.new-listener", new zordon.defense.Subject("port", "8888"),
                "port:8888", null, null, null, null, null, null, "porta nova", false, Map.of(), Clock.systemUTC()
                .instant()));
        assertThat(response.events(10)).hasSize(2);
        assertThat(response.events(10).getFirst()).containsEntry("executed", "NONE")
                .containsEntry("outcome", "OBSERVED");
        assertThat(breakers.wire()).as("nem integridade nem porta nova abrem disjuntor").isEmpty();
    }
}
