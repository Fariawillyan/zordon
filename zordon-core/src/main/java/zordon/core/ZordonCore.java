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
package zordon.core;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.api.zwp.EndpointFile;
import zordon.api.zwp.ZwpCloseCode;
import zordon.core.endpoint.EndpointPublisher;
import zordon.core.event.QueuePolicy;
import zordon.core.event.ZordonEventBus;
import zordon.core.platform.SystemdNotifier;
import zordon.ai.Pricing;
import zordon.ai.registry.AiSettings;
import zordon.ai.registry.ProviderRegistry;
import zordon.api.SessionId;
import zordon.core.chat.ConversationStore;
import zordon.core.chat.IntentRouter;
import zordon.core.chat.PromptComposer;
import zordon.core.chat.TurnManager;
import zordon.core.zwp.ChatMethods;
import zordon.core.zwp.SessionMethods;
import zordon.core.zwp.SystemMethods;
import zordon.core.zwp.VoiceMethods;
import zordon.core.activity.ActivityService;
import zordon.core.trace.LiveTrace;
import zordon.core.voice.SidecarVoiceEngine;
import zordon.core.voice.SpeechPlayer;
import zordon.core.voice.AudioIngest;
import zordon.core.voice.VoiceService;
import zordon.core.voice.VoiceStore;
import zordon.core.zwp.ZwpServer;
import zordon.api.trace.Spec;

/**
 * Composition root do núcleo: monta os componentes, sobe o ZWP e publica o
 * endpoint.
 *
 * <p>Injeção é manual e explícita. Um container de DI aqui adicionaria tempo de
 * inicialização e magia de classpath sem resolver nenhum problema que este projeto
 * tenha (docs/architecture/components.md §6).
 */
@Spec("SPEC-002")
public final class ZordonCore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZordonCore.class);
    private static final Duration LISTEN_TIMEOUT = Duration.ofSeconds(10);

    /** O que este núcleo sabe fazer. Cresce por marco, e nunca antes de ser verdade. */
    private static final List<String> CAPABILITIES = List.of("chat.stream");

    private final ZordonConfig config;
    private final SystemdNotifier systemd;
    private final ZordonEventBus bus;
    private final ZwpServer server;
    private final ConversationStore conversations = new ConversationStore();
    private final ProviderRegistry providers;
    private TurnManager turns;
    private SessionId voiceSession;
    private VoiceService voice;
    private SidecarVoiceEngine engine;
    private SpeechPlayer speech;
    /** Auditoria e motor de permissão (SPEC-014): existem antes da primeira ação com efeito. */
    private final zordon.security.Redactor redactor = new zordon.security.Redactor();
    private zordon.security.AuditLog audit;
    private zordon.security.PermissionEngine permissions;
    private volatile Map<String, Object> auditState = Map.of("chain", "unverified");
    /** Pedido de permissão, notificações e kill switch (SPEC-015). */
    private zordon.core.permission.DesktopApprover approver;
    private zordon.core.notify.NotificationCenter notifications;
    private zordon.core.permission.LockdownService lockdown;
    private zordon.core.permission.OppressorService oppressor;
    /** Execução mediada (SPEC-016). */
    private zordon.core.tools.SkillRuntime tools;
    private zordon.core.tools.WindowsBridge windows;
    private zordon.security.vault.QuarantineVault vault;
    /** Agentes como configuração (SPEC-022). */
    private zordon.core.agents.AgentRegistry agents;
    private zordon.core.agents.AgentService agentRuns;
    /** Detecção e correlação (SPEC-026) e resposta (SPEC-027). */
    private zordon.core.defense.DefenseService defense;
    private zordon.core.defense.DefenseEngine response;
    private zordon.defense.CircuitBreakers breakers;
    private zordon.core.defense.IntegrityWatch integrity;
    private zordon.defense.HostWatch hostWatch;
    /** Preflight e uso de tokens (SPEC-029). */
    private zordon.core.change.Preflight preflight;
    private zordon.core.usage.UsageTracker usage;
    /** Base de conhecimento (SPEC-028). */
    private zordon.core.rag.KnowledgeBase knowledge;
    /** Monitor do sistema (SPEC-024). */
    private zordon.core.automation.AutomationEngine automations;
    private zordon.core.monitor.SystemSampler sampler;
    private zordon.core.monitor.DockerEvents dockerEvents;
    /** Planos duráveis e conclusão verificada (SPEC-023). */
    private zordon.core.tasks.TaskRunner tasks;
    /** Memória de longo prazo e destilação (SPEC-021). */
    private zordon.memory.ZordonDatabase database;
    private zordon.memory.SqliteMemoryStore memory;
    private zordon.core.memory.Distiller distiller;
    /** Servidores MCP do {@code config.toml} (SPEC-020). */
    private zordon.core.mcp.McpManager mcp;
    /**
     * O andaime da montagem.
     *
     * <p>Estes não são {@code final} porque a construção foi dividida em fases
     * (SPEC-035): um construtor de 195 linhas não cabia no limite do
     * [padrões §5](../../../../../docs/process/code-standards.md#5-complexidade),
     * e um método auxiliar não pode atribuir campo {@code final}. São escritos
     * uma vez, durante a construção, e nunca depois.
     */
    private IntentRouter router;
    private PromptComposer prompts;
    private zordon.security.Gatekeeper gatekeeper;
    private zordon.security.ProcessRunner runner;
    private zordon.api.security.ZPath userHome;
    private zordon.security.PathPolicy policy;
    private zordon.core.tools.ModelToolCaller modelTools;
    private zordon.core.agents.AgentRunner agentRunner;

    private final java.util.concurrent.atomic.AtomicReference<zordon.ai.cli.CliRunner> cliRunner =
            new java.util.concurrent.atomic.AtomicReference<>();
    private LiveTrace trace;
    private ActivityService activity;
    private final String startId;
    private final Instant startedAt;
    private final String token;

    public ZordonCore(ZordonConfig config, SystemdNotifier systemd) {
        this(config, systemd, System.getenv());
    }

    /**
     * @param environment de onde as referências {@code env:} do {@code config.toml}
     *     tiram as chaves. Injetado para que um teste não use, sem querer, a chave
     *     de quem o está rodando.
     */
    public ZordonCore(ZordonConfig config, SystemdNotifier systemd, Map<String, String> environment) {
        this.config = config;
        this.systemd = systemd;
        this.startId = StartId.generate();
        this.startedAt = Instant.now();
        this.token = EndpointPublisher.newToken();
        this.bus = new ZordonEventBus(startId);
        this.server = new ZwpServer(new InetSocketAddress(config.bindAddress(), config.port()), token);
        this.providers = providers(environment);
        wireChatAndVoice(config, environment);
        wireSecurity(config, environment);
        wireMemory(config);
        wireDefense(config, environment);
        wireAgentsAndTasks(config, environment);
        wireAutomation(config, environment);
        wireTurnCallbacks();
    }

    private ProviderRegistry providers(Map<String, String> environment) {
        return ProviderRegistry.build(
                AiSettings.load(config.home().resolve("config.toml")),
                Pricing.load(config.home().resolve("pricing.toml")),
                environment,
                // O provider por assinatura roda pelo caminho mediado, montado mais abaixo (SPEC-018).
                new zordon.ai.cli.CliRunner() {
                    @Override
                    public Result run(java.util.List<String> argv, String stdin, java.time.Duration timeout)
                            throws Exception {
                        zordon.ai.cli.CliRunner current = cliRunner.get();
                        if (current == null) {
                            throw new IllegalStateException("o núcleo ainda está iniciando");
                        }
                        return current.run(argv, stdin, timeout);
                    }

                    @Override
                    public boolean available(String program) {
                        return securitySettings(environment).catalog().containsKey(program);
                    }
                });
    }

    /** Conversa e voz: o turno, a captura, o motor, a fala e o narrador. */
    private void wireChatAndVoice(ZordonConfig config, Map<String, String> environment) {
        this.router = new IntentRouter();
        this.prompts = new PromptComposer();
        this.turns = new TurnManager(bus, conversations, router, prompts, providers);
        AudioIngest audio = new AudioIngest(
                server,
                level -> bus.publish(EventType.VOICE_LEVEL, level),
                alert -> bus.publish(EventType.SYSTEM_ALERT, alert),
                System::nanoTime);
        server.onBinary(audio);
        // O motor de voz é o sidecar zordon-voice, num socket Unix (SPEC-011, ADR-0028).
        this.engine = new SidecarVoiceEngine(Path.of(
                environment.getOrDefault("ZORDON_VOICE_SOCKET", "/run/zordon-voice/voice.sock")));
        this.voice = new VoiceService(
                new VoiceStore(config.home().resolve("state").resolve("voice.json")),
                engine,
                server,
                snapshot -> bus.publish(EventType.VOICE_STATE, snapshot),
                Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("voice-deadlines").factory()),
                audio,
                System::nanoTime);
        this.trace = new LiveTrace(config.home().resolve("trace"), Clock.systemDefaultZone());
        // A fala sai pelo narrador (SPEC-012) e é tocada no host pelo motor (SPEC-011).
        this.speech = new SpeechPlayer(engine, voice, server, server::sendBinary);
        this.activity = new ActivityService(bus, speech, Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("activity-ticks").factory()));
    }

    /** O núcleo de confiança e a execução mediada (SPEC-014 a SPEC-017). */
    private void wireSecurity(ZordonConfig config, Map<String, String> environment) {
        this.audit = new zordon.security.SqliteAuditLog(config.home().resolve("state").resolve("audit.db"),
                redactor, Clock.systemUTC());
        this.approver = new zordon.core.permission.DesktopApprover(server);
        this.permissions = new zordon.security.DefaultPermissionEngine(securitySettings(environment).paths(),
                securitySettings(environment).validator(), redactor, () -> approver);
        this.notifications = new zordon.core.notify.NotificationCenter(
                config.home().resolve("state").resolve("notifications.db"), Clock.systemUTC(),
                message -> bus.publish(EventType.SECURITY_NOTIFICATION, message.payload()));
        // Antes do gatekeeper porque é ele quem consulta o modo a cada ação.
        this.oppressor = new zordon.core.permission.OppressorService(
                config.home().resolve("state").resolve("oppressor.hash"), Clock.systemUTC(),
                (entered, payload) -> bus.publish(entered ? EventType.OPPRESSOR_ENTERED : EventType.OPPRESSOR_EXITED,
                        payload));
        this.gatekeeper = new zordon.security.Gatekeeper(permissions, audit, oppressor::active);
        this.runner = new zordon.security.ProcessRunner(
                securitySettings(environment).validator());
        this.userHome = zordon.api.security.ZPath.ofWsl(
                environment.getOrDefault("HOME", System.getProperty("user.home")));
        this.policy = securitySettings(environment).paths();
        this.windows = new zordon.core.tools.WindowsBridge(server);
        cliRunner.set(new zordon.core.tools.GatekeptCliRunner(gatekeeper, runner, config.home().resolve("cli-work"),
                program -> securitySettings(environment).catalog().containsKey(program)));
        this.vault = new zordon.security.vault.QuarantineVault(config.home().resolve("quarantine"),
                Clock.systemUTC());
        this.lockdown = new zordon.core.permission.LockdownService(
                config.home().resolve("state").resolve("lockdown.json"), Clock.systemUTC(),
                (entered, payload) -> bus.publish(entered ? EventType.LOCKDOWN_ENTERED : EventType.LOCKDOWN_EXITED,
                        payload));
        this.tools = new zordon.core.tools.SkillRuntime(gatekeeper, bus, lockdown::active)
                .register(windows.openTool())
                .register(zordon.core.tools.FileTools.list(policy, userHome))
                .register(zordon.core.tools.FileTools.read(policy, userHome))
                .register(zordon.core.tools.FileTools.write(policy, userHome))
                .register(zordon.core.tools.ProcessTools.metrics())
                .register(zordon.core.tools.NetTools.httpCheck())
                .register(zordon.core.tools.ProcessTools.gitStatus(policy, userHome, runner))
                .register(zordon.core.tools.ProcessTools.build(policy, userHome, runner))
                .register(zordon.core.tools.QuarantineTools.quarantine(policy, userHome, vault))
                .register(zordon.core.tools.QuarantineTools.restore(policy, vault));
    }

    /** Memória de longo prazo e destilação (SPEC-021). */
    private void wireMemory(ZordonConfig config) {
        this.database = new zordon.memory.ZordonDatabase(config.home().resolve("zordon.db"), Clock.systemUTC());
        this.memory = database.memory();
        java.util.function.Consumer<zordon.memory.Fact> remembered = fact -> bus.publish(EventType.MEMORY_WRITTEN,
                Map.of("kind", fact.kind().name(), "id", fact.id(), "summary",
                        fact.content().length() > 80 ? fact.content().substring(0, 80) + "…" : fact.content()));
        conversations.recordTo(new zordon.core.chat.ConversationStore.Recorder() {
            @Override
            public void session(String sessionId, String title, Instant startedAt) {
                memory.session(sessionId, title, startedAt);
            }

            @Override
            public void message(String sessionId, String turnId, String role, String text, Instant ts) {
                memory.message(sessionId, turnId, role, text, ts);
            }
        });
        tools.register(zordon.core.memory.MemoryTools.remember(memory, Clock.systemUTC(), remembered))
                .register(zordon.core.memory.MemoryTools.search(memory, Clock.systemUTC(), java.time.ZoneId.systemDefault()))
                .onCompleted(new zordon.core.memory.WorkObserver(memory, policy.workspaceRoots(), Clock.systemUTC(),
                        java.time.ZoneId.systemDefault(), remembered)::completed);
        turns.onRecall(new zordon.core.memory.MemoryContext(memory, Clock.systemUTC(), java.time.ZoneId.systemDefault()));
        this.distiller = new zordon.core.memory.Distiller(memory, providers, redactor, Clock.systemUTC(),
                distillEnabled(config.home().resolve("config.toml")), remembered);
    }

    /** Detecção, integridade e os servidores MCP que ela vigia (SPEC-020, SPEC-026). */
    private void wireDefense(ZordonConfig config, Map<String, String> environment) {
        this.defense = new zordon.core.defense.DefenseService(database.findings(), notifications, bus, redactor, Clock.systemUTC(),
                System::nanoTime);
        this.integrity = zordon.core.defense.IntegrityWatch.of(config.home(), defense::integrityChanged);
        this.breakers = new zordon.defense.CircuitBreakers(Clock.systemUTC());
        this.hostWatch = new zordon.defense.HostWatch(Path.of("/proc"),
                Path.of(environment.getOrDefault("HOME", System.getProperty("user.home"))), Clock.systemUTC(),
                defense::observe);
        tools.observedBy(defense).breakerBy(actor -> breakers.isOpen(subjectOf(actor)));
        turns.onCompleted(done -> {
            distiller.completed(done);
            defense.modelOutput(done.turn().value(), done.answer());
        });
        this.mcp = new zordon.core.mcp.McpManager(
                zordon.core.mcp.McpManager.load(config.home().resolve("config.toml")),
                new zordon.core.mcp.McpManager.Deps(gatekeeper, runner, tools, notifications),
                new zordon.core.mcp.McpManager.Dirs(config.home().resolve("state"), config.home().resolve("mcp-work")),
                Clock.systemUTC(), System::nanoTime).onAlert(alert -> {
                    bus.publish(EventType.SYSTEM_ALERT, alert);
                    if ("drift".equals(alert.get("event"))) {
                        defense.mcpDrift(String.valueOf(alert.get("server")), String.valueOf(alert.get("detail")));
                    }
                });
    }

    /** Agentes, planos, conhecimento e preflight (SPEC-022, SPEC-023, SPEC-028, SPEC-029). */
    private void wireAgentsAndTasks(ZordonConfig config, Map<String, String> environment) {
        zordon.core.agents.TurnScopes scopes = new zordon.core.agents.TurnScopes();
        this.modelTools = new zordon.core.tools.ModelToolCaller(tools, scopes);
        this.agents = new zordon.core.agents.AgentRegistry(config.home().resolve("agents"));
        this.agentRunner = new zordon.core.agents.AgentRunner(providers, prompts, modelTools,
                bus).onSuspended(this::agentSuspended);
        tools.register(new zordon.core.agents.DelegateTool(agents, scopes, agentRunner, System::nanoTime))
                .register(zordon.core.tools.ProcessTools.dockerPs(userHome, runner))
                .register(zordon.core.tools.ProcessTools.dockerLogs(userHome, runner));
        this.agentRuns = new zordon.core.agents.AgentService(agents, agentRunner, System::nanoTime);
        zordon.core.tools.SkillRuntime toolsForChecks = tools;
        this.tasks = new zordon.core.tasks.TaskRunner(database.tasks(),
                new zordon.core.tasks.Planner(providers, agents, () -> toolsForChecks.list().stream()
                        .filter(row -> "green".equals(row.get("risk")))
                        .map(row -> String.valueOf(row.get("name")))
                        .filter(name -> toolsForChecks.resolveWireName(name).isPresent())
                        .collect(java.util.stream.Collectors.toSet())),
                new zordon.core.tasks.Verifier(providers, tools),
                new zordon.core.tasks.TaskRunner.Agents(agents, agentRunner), bus, System::nanoTime);
        tools.register(zordon.core.tasks.TaskTools.create(tasks));
        this.knowledge = new zordon.core.rag.KnowledgeBase(database.knowledge(), zordon.core.rag.KnowledgeBase.defaultRoots(
                config.home().resolve("config.toml"), repoRoot(environment)));
        tools.register(zordon.core.rag.RagTools.search(knowledge));
        this.preflight = new zordon.core.change.Preflight(database.tasks(), knowledge, agents, notifications);
        this.usage = new zordon.core.usage.UsageTracker(database.usage(), bus, Clock.systemDefaultZone());
        tools.register(zordon.core.change.ChangeTools.plan(preflight));
    }

    /** Resposta da defesa, monitor e automações (SPEC-024, SPEC-025, SPEC-027). */
    private void wireAutomation(ZordonConfig config, Map<String, String> environment) {
        java.util.concurrent.atomic.AtomicReference<zordon.core.automation.AutomationEngine> automationRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        this.response = new zordon.core.defense.DefenseEngine(breakers, database.securityEvents(), notifications, bus,
                new zordon.core.defense.DefenseEngine.Actions() {
                    @Override
                    public boolean isolateMcp(String server) {
                        return mcp.isolate(server);
                    }

                    @Override
                    public boolean cancelAgent(String agent) {
                        return agentRuns.cancelAgent(agent) >= 0;
                    }

                    @Override
                    public void lockdown(String reason) {
                        ZordonCore.this.lockdown.enter(reason, "defense");
                    }
                }, Clock.systemUTC());
        defense.respondWith(response::respond);
        this.sampler = zordon.core.monitor.SystemSampler.system(() ->
                automationRef.get() != null && automationRef.get().hasConditions());
        zordon.core.automation.AutomationNotifier automationNotifier = new zordon.core.automation.AutomationNotifier(
                notifications, windows::notify, Clock.systemDefaultZone());
        zordon.core.automation.WorkflowEngine workflow = new zordon.core.automation.WorkflowEngine(
                database.tasks(), database.automations(),
                new zordon.core.automation.WorkflowEngine.Engines(tools, agents, agentRunner, automationNotifier),
                bus, Clock.systemDefaultZone(), System::nanoTime);
        this.automations = new zordon.core.automation.AutomationEngine(config.home().resolve("automations"),
                new zordon.core.automation.AutomationEngine.Stores(database.automations(), database.tasks()),
                new zordon.core.automation.AutomationEngine.Engines(tools, agents, workflow, automationNotifier),
                new zordon.core.automation.AutomationEngine.Env(bus, lockdown::active, sampler::latest),
                Clock.systemDefaultZone(), System::nanoTime);
        automationRef.set(automations);
        tools.register(zordon.core.automation.AutomationTools.propose(automations));
        this.dockerEvents = securitySettings(environment).catalog().containsKey("docker")
                ? new zordon.core.monitor.DockerEvents(gatekeeper, runner, config.home().resolve("monitor-work"),
                        payload -> bus.publish(EventType.CONTAINER_EVENT, payload),
                        zordon.core.monitor.DockerEvents.defaultBackoff())
                : null;
    }

    /** O que o turno chama de volta: só depois que todo o resto existe. */
    private void wireTurnCallbacks() {
        turns.onAgents(agents);
        turns.onSuspended(this::agentSuspended);
        router.knowAgents(id -> agents.find(id).isPresent());
        turns.onToolCalls(modelTools);
        turns.onTool((tool, args, source, turnId) -> tools.invoke(tool, args,
                zordon.api.security.Principal.user("voice".equals(source)
                        ? zordon.api.security.RequestOrigin.VOICE : zordon.api.security.RequestOrigin.UI),
                turnId).thenApply(zordon.core.tools.ToolResult::text));
    }

    /** O disjuntor de uma execução abriu: aviso HIGH, com os sinais (SPEC-022 CA-5). */
    private void agentSuspended(zordon.core.agents.AgentProfile agent, String reason) {
        notifications.publish(notifications.message(zordon.api.security.Severity.HIGH, "AI_DEFENSE",
                "O agente " + agent.id() + " foi suspenso",
                "A execução do agente " + agent.id() + " parou: " + reason + ".",
                "Negações seguidas, tentativa acima do teto ou repetição sem progresso são o comportamento de um agente"
                        + " desviado do objetivo, por exemplo por conteúdo malicioso que ele leu.",
                "disjuntor da execução do agente (SPEC-022)",
                "A execução foi encerrada; nenhuma ação a mais foi feita.",
                "agente " + agent.id(), true,
                "Encerrado. Um pedido novo começa do zero.",
                List.of("Ver o que o agente fez no Live Trace", "Pedir de novo")));
    }

    /** O repositório do Zordon no disco, quando ele estiver lá: a fonte da documentação (SPEC-028). */
    private static Path repoRoot(Map<String, String> environment) {
        String declared = environment.get("ZORDON_REPO");
        if (declared != null && java.nio.file.Files.isDirectory(Path.of(declared))) {
            return Path.of(declared);
        }
        Path candidate = Path.of(environment.getOrDefault("HOME", System.getProperty("user.home")), "zordon");
        return java.nio.file.Files.isDirectory(candidate.resolve("docs")) ? candidate : null;
    }

    /** O sujeito do disjuntor a partir do ator: {@code agent:x}, {@code automation:y} ou o ator. */
    private static String subjectOf(zordon.api.security.Principal actor) {
        String name = actor.actor();
        if (name.startsWith("agent:")) {
            return "agent:" + name.substring("agent:".length());
        }
        if (name.startsWith("automation:")) {
            return "automation:" + name.substring("automation:".length());
        }
        return "actor:" + name;
    }

    /** {@code [memory] distill = false} desliga a destilação; o padrão é ligada (SPEC-021 §3). */
    private static boolean distillEnabled(Path configToml) {
        if (!java.nio.file.Files.exists(configToml)) {
            return true;
        }
        try {
            Boolean distill = org.tomlj.Toml.parse(configToml).getBoolean("memory.distill");
            return distill == null || distill;
        } catch (java.io.IOException | RuntimeException e) {
            return true;
        }
    }

    private zordon.security.SecuritySettings securitySettings;

    /** Política inválida não derruba o núcleo: vale a padrão, que é a mais restrita, e isso é avisado. */
    private zordon.security.SecuritySettings securitySettings(Map<String, String> environment) {
        if (securitySettings == null) {
            String home = environment.getOrDefault("HOME", System.getProperty("user.home"));
            try {
                securitySettings = zordon.security.SecuritySettings.load(config.home().resolve("config.toml"), home,
                        environment.get("PATH"));
            } catch (java.io.IOException e) {
                log.warn("política de segurança do config.toml ignorada: {}", e.getMessage());
                try {
                    securitySettings = zordon.security.SecuritySettings.load(
                            config.home().resolve("config.toml.ausente"), home, environment.get("PATH"));
                } catch (java.io.IOException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        }
        return securitySettings;
    }

    /** Confere a cadeia da auditoria; quebrada, é alerta crítico (SPEC-014 §13). */
    private void verifyAudit() {
        zordon.security.AuditLog.Verification verification = audit.verify(1000);
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("entries", audit.entries());
        state.put("chain", verification.ok() ? "ok" : "broken");
        state.put("checked", verification.checked());
        state.put("verifiedAt", Instant.now().toString());
        if (!verification.ok()) {
            state.put("firstBroken", verification.firstBroken());
            Map<String, Object> alert = new java.util.LinkedHashMap<>();
            alert.put("severity", "critical");
            alert.put("message", "a cadeia da auditoria está quebrada a partir da linha "
                    + verification.firstBroken() + "; ações acima de GREEN ficam negadas até verificação");
            bus.publish(EventType.SYSTEM_ALERT, alert);
            log.error("auditoria: cadeia quebrada na linha {}", verification.firstBroken());
            defense.auditChainBroken("cadeia quebrada a partir da linha " + verification.firstBroken());
            lockdown.enter("a cadeia da auditoria está quebrada", "audit");
            notifications.publish(notifications.message(zordon.api.security.Severity.CRITICAL, "SECURITY",
                    "A auditoria foi alterada por fora",
                    "A cadeia de hash da auditoria não confere a partir da linha " + verification.firstBroken() + ".",
                    "Uma linha só muda assim se alguém mexer no arquivo audit.db fora do Zordon.",
                    "verificação da auditoria na inicialização do núcleo",
                    "O Zordon entrou em só leitura (lockdown).",
                    "~/.zordon/state/audit.db",
                    true,
                    "Só leitura: nenhuma ação acima de GREEN executa.",
                    java.util.List.of("Conferir o arquivo e retomar na tela", "Manter pausado")));
        } else {
            log.info("auditoria: cadeia íntegra ({} linhas conferidas de {})", verification.checked(), audit.entries());
        }
        auditState = Map.copyOf(state);
    }

    public static void main(String[] args) throws Exception {
        ZordonCore core = new ZordonCore(ZordonConfig.fromEnvironment(), new SystemdNotifier());
        Runtime.getRuntime().addShutdownHook(new Thread(core::close, "zordon-shutdown"));
        core.start();
        Thread.currentThread().join();
    }

    public void start() throws InterruptedException {
        registerMethods();
        startSubsystems();
        listenAndAnnounce();
        startBackground();
    }

    /** Os métodos que o protocolo expõe. Nada aqui bloqueia. */
    private void registerMethods() {
        zordon.core.zwp.MonitorMethods monitor =
                new zordon.core.zwp.MonitorMethods(sampler, dockerEvents);
        new SessionMethods(bus, version(), startedAt, CAPABILITIES).registerOn(server);
        new ChatMethods(turns, conversations).registerOn(server);
        new SystemMethods(version(), startedAt,
                        new SystemMethods.Runtime(bus, turns, providers, server::connectedClients),
                        new SystemMethods.Reports(voice::status,
                                () -> Map.of("dir", trace.dir().toString(), "file", trace.today().toString(),
                                        "activity", activity.state()),
                                () -> Map.of("audit", auditState,
                                        "programs", securitySettings.catalog().keySet().stream().sorted().toList())))
                .with("rag", knowledge::status)
                .with("usage", () -> usage.summary(7))
                .with("monitor", monitor::status)
                .with("automation", automations::diagnostics)
                .with("defense", () -> {
                    Map<String, Object> out = new java.util.LinkedHashMap<>(defense.diagnostics());
                    out.put("integrity", integrity.status());
                    out.put("host", hostWatch.status());
                    out.put("breakers", response.breakers());
                    return out;
                })
                .with("memory", () -> {
                    Map<String, Object> stats = new java.util.LinkedHashMap<>(memory.stats());
                    stats.put("tasks", database.tasks().taskStats());
                    return stats;
                })
                .registerOn(server);
        new zordon.core.zwp.SecurityMethods(notifications, lockdown, oppressor, approver, () -> auditState)
                .registerOn(server);
        new zordon.core.zwp.ToolMethods(tools, windows, vault).registerOn(server);
        new zordon.core.zwp.McpMethods(mcp).registerOn(server);
        new zordon.core.zwp.MemoryMethods(memory).registerOn(server);
        new zordon.core.zwp.AgentMethods(agents, agentRuns).registerOn(server);
        monitor.registerOn(server);
        new zordon.core.zwp.TaskMethods(database.tasks(), tasks).automations(automations).registerOn(server);
        new zordon.core.zwp.AutomationMethods(automations).registerOn(server);
        new zordon.core.zwp.UsageMethods(usage, preflight).registerOn(server);
        new zordon.core.zwp.RagMethods(knowledge).registerOn(server);
        new zordon.core.zwp.DefenseMethods(response, defense, mcp).registerOn(server);
    }

    /** Auditoria conferida, trace, narrador e voz de pé — antes de aceitar conexão. */
    private void startSubsystems() {
        verifyAudit();
        trace.start(bus);
        activity.start();
        voice.onTranscript(transcript -> bus.publish(EventType.VOICE_STOPPED, transcript));
        voice.onWakeOutcome(wake -> bus.publish(EventType.VOICE_WAKE, wake));
        // A palavra (SPEC-013): se o Zordon falava, a fala para; depois, o tom de escuta.
        voice.onWake(interrupting -> {
            if (interrupting) {
                speech.interrupt();
            }
            speech.cue();
        });
        voice.onCommand(this::heard);
        engine.start();
        speech.start();
        new VoiceMethods(voice).registerOn(server);
        forwardEventsToClients();
    }

    /** Escuta, publica o endpoint e só então se declara pronto ao systemd. */
    private void listenAndAnnounce() throws InterruptedException {

        server.start();
        if (!server.awaitListening(LISTEN_TIMEOUT)) {
            throw new IllegalStateException("ZWP não começou a escutar em " + LISTEN_TIMEOUT);
        }

        // A porta real, não a configurada: com porta 0 o sistema escolhe uma, e é
        // essa que precisa ir para o endpoint.json.
        EndpointFile endpoint = new EndpointPublisher(config).publish(startId, startedAt, token, port());

        // Só agora o serviço se declara pronto: com a porta aceitando conexões e o
        // endereço publicado. É o que Type=notify compra (docs/operations/install.md §3).
        systemd.status("ZWP em " + endpoint.endpoints().getFirst());
        systemd.ready();

        // Os servidores MCP conectam em segundo plano: o núcleo já está pronto (SPEC-020 CA-1).
    }

    /**
     * Uma fala da escuta: ou mexe no OPPRESSOR MODE, ou vira turno (SPEC-036 CA-9).
     *
     * <p>A checagem vem antes do turno porque um pedido de modo não pode virar
     * conversa: passar pelo modelo tornaria não-determinístico algo que decide
     * se o motor de permissão continua no caminho.
     */
    private void heard(String text) {
        switch (zordon.core.permission.OppressorPhrase.of(text)) {
            // A voz só abre o pedido de senha na tela; nunca autoriza (ADR-0030).
            case ENTER -> bus.publish(EventType.OPPRESSOR_PROMPT, java.util.Map.of("heard", text));
            case EXIT -> oppressor.exit("voice");
            case NONE -> turns.send(voiceConversation(), text, "voice");
        }
    }

    /** O que sobe depois de pronto: MCP, destilação, monitor e as tarefas interrompidas. */
    private void startBackground() {
        mcp.start();
        distiller.start();
        sampler.start();
        if (dockerEvents != null) {
            dockerEvents.start();
        }
        // Tarefas que o núcleo deixou pela metade: bloqueadas e avisadas, nunca reexecutadas sozinhas (SPEC-023 CA-4).
        for (zordon.memory.TaskStore.TaskView interrupted : tasks.recover()) {
            notifications.publish(notifications.message(zordon.api.security.Severity.WARNING, "SYSTEM",
                    "Uma tarefa foi interrompida",
                    "O núcleo reiniciou no meio da tarefa \"" + interrupted.goal() + "\".",
                    "Uma etapa pode ter feito só metade; repetir sozinho poderia duplicar um efeito.",
                    "retomada de tarefas na inicialização (SPEC-023)",
                    "A tarefa ficou bloqueada; nada foi reexecutado.",
                    "tarefa " + interrupted.id(), true,
                    "Bloqueada, esperando você.",
                    List.of("Continuar pela tela", "Cancelar")));
        }
        automations.start();
        integrity.start();
        usage.start();
        hostWatch.start();
        bus.publish(EventType.CORE_STARTED, Map.of("startId", startId, "version", version()));
        log.info("Zordon {} pronto — startId {}", version(), startId);
    }

    /** A conversa das perguntas por voz: uma só, criada na primeira. */
    private synchronized SessionId voiceConversation() {
        if (voiceSession == null || !conversations.exists(voiceSession)) {
            voiceSession = conversations.newSession("Conversa por voz");
        }
        return voiceSession;
    }

    public int port() {
        return server.getPort();
    }

    public ZordonEventBus events() {
        return bus;
    }

    @Override
    public void close() {
        log.info("encerrando o núcleo");
        systemd.stopping();
        speech.close();
        engine.close();
        automations.close();
        integrity.close();
        usage.close();
        hostWatch.close();
        mcp.close();
        try {
            server.stop(ZwpCloseCode.SHUTTING_DOWN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        distiller.close();
        sampler.close();
        if (dockerEvents != null) {
            dockerEvents.close();
        }
        bus.close();
        audit.close();
        notifications.close();
        database.close();
        // O endpoint.json fica onde está: o Zordon não apaga arquivos (ADR-0015).
        // O cliente descobre que o núcleo saiu pela conexão, não pela ausência do
        // arquivo, e o token deste boot deixa de valer no próximo.
    }

    /**
     * Duas assinaturas, porque as políticas de fila diferem: tópicos obrigatórios
     * falham alto em vez de descartar em silêncio (ADR-0011).
     */
    private void forwardEventsToClients() {
        Set<String> droppable = Topic.ALL.stream()
                .filter(topic -> !Topic.MANDATORY.contains(topic))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        bus.subscribe("zwp-mandatory", Topic.MANDATORY, QueuePolicy.rejectPublish(512), server::broadcastEvent);
        bus.subscribe("zwp", droppable, QueuePolicy.dropOldest(1_024), server::broadcastEvent);
    }

    private String version() {
        return Optional.ofNullable(ZordonCore.class.getPackage().getImplementationVersion())
                .orElse("0.0.0-dev");
    }
}
