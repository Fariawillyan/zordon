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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventEnvelope;
import zordon.api.event.Topic;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;
import zordon.core.agents.AgentRegistry;
import zordon.core.event.EventSubscription;
import zordon.core.event.QueuePolicy;
import zordon.core.event.ZordonEventBus;
import zordon.core.monitor.SystemSampler;
import zordon.core.tools.SkillRuntime;
import zordon.memory.AutomationStateStore;
import zordon.memory.TaskStore;

/** Aprovação, gatilhos e exclusão mútua das execuções da SPEC-025. */
@Spec("SPEC-025")
public final class AutomationEngine implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AutomationEngine.class);
    private final Path directory;
    private final AutomationStateStore states;
    private final TaskStore tasks;
    private final SkillRuntime tools;
    private final AgentRegistry agents;
    private final WorkflowEngine workflow;
    private final WorkflowEngine.Notifier notifier;
    private final ZordonEventBus bus;
    private final BooleanSupplier lockdown;
    private final Supplier<SystemSampler.Snapshot> metrics;
    private final Clock clock;
    private final LongSupplier nanos;
    private final Map<String, AutomationSpec> specs = new LinkedHashMap<>();
    private final Map<String, AutomationSpec> proposals = new LinkedHashMap<>();
    private final Map<String, TriggerState> triggers = new LinkedHashMap<>();
    private final Map<String, Thread> active = new LinkedHashMap<>();
    private final Map<String, String> activeTasks = new LinkedHashMap<>();
    private final List<Map<String, Object>> invalid = new ArrayList<>();
    private ScheduledExecutorService timer;
    private EventSubscription subscription;
    private boolean closed;

    public AutomationEngine(Path directory, AutomationStateStore states, TaskStore tasks, SkillRuntime tools,
            AgentRegistry agents, WorkflowEngine workflow, WorkflowEngine.Notifier notifier, ZordonEventBus bus,
            BooleanSupplier lockdown, Supplier<SystemSampler.Snapshot> metrics, Clock clock, LongSupplier nanos) {
        this.directory = directory;
        this.states = states;
        this.tasks = tasks;
        this.tools = tools;
        this.agents = agents;
        this.workflow = workflow;
        this.notifier = notifier;
        this.bus = bus;
        this.lockdown = lockdown;
        this.metrics = metrics;
        this.clock = clock;
        this.nanos = nanos;
    }

    public synchronized void start() {
        reload();
        subscription = bus.subscribe("automations", Topic.ALL, QueuePolicy.dropOldest(256), this::event);
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("zordon-automations").factory());
        timer.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                log.warn("gatilhos: {}", e.toString());
            }
        }, 0, 1, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(this::reload, 5, 5, TimeUnit.SECONDS);
    }

    public synchronized void reload() {
        if (closed) {
            return;
        }
        Map<String, AutomationSpec> loaded = new LinkedHashMap<>();
        invalid.clear();
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".toml")).sorted().toList()) {
                    try {
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 262_144) {
                            throw new IllegalArgumentException("arquivo inválido ou maior que 256 KiB");
                        }
                        AutomationSpec spec = AutomationSpec.parseToml(Files.readString(file));
                        if (!file.getFileName().toString().equals(spec.id() + ".toml")) {
                            throw new IllegalArgumentException("nome do arquivo não corresponde ao id");
                        }
                        validate(spec);
                        loaded.put(spec.id(), spec);
                        if (!spec.equals(specs.get(spec.id()))) {
                            triggers.put(spec.id(), new TriggerState());
                        }
                    } catch (IOException | RuntimeException e) {
                        invalid.add(Map.of("id", file.getFileName().toString(), "name", file.getFileName().toString(),
                                "enabled", false, "reason", String.valueOf(e.getMessage())));
                    }
                }
            }
            specs.clear();
            specs.putAll(loaded);
            triggers.keySet().retainAll(loaded.keySet());
        } catch (IOException e) {
            log.warn("automações indisponíveis: {}", e.toString());
        }
    }

    public synchronized Map<String, Object> propose(Map<String, Object> raw) {
        AutomationSpec spec = AutomationSpec.fromMap(raw);
        validate(spec);
        if (specs.containsKey(spec.id()) || Files.exists(directory.resolve(spec.id() + ".toml"))) {
            throw new IllegalArgumentException("já existe uma automação com este id");
        }
        if (proposals.size() >= 100) {
            throw new IllegalArgumentException("há 100 propostas pendentes; recuse ou aprove antes de criar mais");
        }
        String id = "proposal-" + UUID.randomUUID();
        proposals.put(id, spec);
        notifier.notify(spec, "Automação aguardando sua aprovação", summary(spec), "info");
        return Map.of("proposalId", id, "summary", summary(spec));
    }

    public synchronized String approve(String proposalId) {
        AutomationSpec spec = Optional.ofNullable(proposals.get(proposalId))
                .orElseThrow(() -> new IllegalArgumentException("proposta desconhecida"));
        validate(spec);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(spec.id() + ".toml"), spec.toToml(), StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new IllegalArgumentException("não foi possível criar a automação: " + e.getMessage(), e);
        }
        proposals.remove(proposalId);
        reload();
        return spec.id();
    }

    public synchronized boolean reject(String proposalId) {
        return proposals.remove(proposalId) != null;
    }

    public synchronized boolean enable(String id, boolean enabled) {
        require(id);
        states.automationDisabled(id, !enabled, enabled ? "ativada na tela" : "desativada na tela");
        triggers.put(id, new TriggerState());
        return enabled;
    }

    private boolean enabled(AutomationSpec spec) {
        AutomationStateStore.State state = states.automationState(spec.id());
        return !state.disabled() && (spec.enabled() || "ativada na tela".equals(state.reason()));
    }

    public synchronized boolean hasConditions() {
        return specs.values().stream().anyMatch(spec -> enabled(spec) && spec.trigger() instanceof AutomationSpec.Condition);
    }

    public synchronized Map<String, Object> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AutomationSpec spec : specs.values()) {
            AutomationStateStore.State state = states.automationState(spec.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", spec.id());
            row.put("name", spec.name());
            row.put("trigger", spec.trigger().summary());
            row.put("enabled", enabled(spec));
            row.put("failures", state.failures());
            row.put("running", active.containsKey(spec.id()));
            if (state.lastFiredAt() != null) {
                row.put("lastFiredAt", state.lastFiredAt().toString());
            }
            if (state.reason() != null) {
                row.put("reason", state.reason());
            }
            rows.add(row);
        }
        rows.addAll(invalid);
        return Map.of("automations", rows, "proposals", proposals.entrySet().stream().map(entry ->
                Map.of("proposalId", entry.getKey(), "id", entry.getValue().id(), "name", entry.getValue().name(),
                        "summary", summary(entry.getValue()))).toList());
    }

    public synchronized Map<String, Object> diagnostics() {
        long enabled = specs.values().stream().filter(this::enabled).count();
        return Map.of("enabled", enabled, "disabled", specs.size() - enabled, "invalid", invalid.size(),
                "proposals", proposals.size(), "running", active.size(), "tokensToday", workflow.tokensToday());
    }

    public synchronized Optional<String> fire(String id, Map<String, Object> event) {
        return launch(require(id), List.of(event), null);
    }

    public synchronized boolean resume(String taskId) {
        TaskStore.TaskView task = tasks.task(taskId).orElse(null);
        if (task == null || !"blocked".equals(task.state()) || !task.origin().startsWith("automation:")) {
            return false;
        }
        AutomationSpec original = workflow.definition(task);
        AutomationSpec current = specs.get(original.id());
        if (current == null || !enabled(current)) {
            return false;
        }
        validate(original);
        return launch(original, List.of(Map.of()), taskId).isPresent();
    }

    public synchronized boolean cancel(String taskId) {
        TaskStore.TaskView task = tasks.task(taskId).orElse(null);
        if (task == null || !task.origin().startsWith("automation:")
                || Set.of("done", "failed", "cancelled").contains(task.state())) {
            return false;
        }
        tasks.taskState(taskId, "cancelled", "cancelada na tela");
        String id = task.origin().substring("automation:".length());
        if (taskId.equals(activeTasks.get(id)) && active.get(id) != null) {
            active.get(id).interrupt();
        }
        return true;
    }

    /** Chamável nos testes sem dormir: relógio e amostra são injetados. */
    public synchronized void tick() {
        if (closed) {
            return;
        }
        Instant now = clock.instant();
        long monotonic = nanos.getAsLong();
        SystemSampler.Snapshot sample = metrics.get();
        for (AutomationSpec spec : specs.values()) {
            if (!enabled(spec)) {
                continue;
            }
            TriggerState trigger = triggers.get(spec.id());
            if (spec.trigger() instanceof AutomationSpec.Condition condition) {
                if (DurationSupport.fresh(sample.at(), now)
                        && trigger.condition(condition, sample.metric(condition.metric()), monotonic)) {
                    launch(spec, List.of(Map.of("metric", condition.metric(), "value", sample.metric(condition.metric()))), null);
                }
            } else {
                List<Instant> due = trigger.due(spec.trigger(), states.automationState(spec.id()).lastFiredAt(), now, monotonic);
                if (!due.isEmpty()) {
                    launch(spec, due.stream().map(at -> Map.<String, Object>of("scheduledAt", at.toString())).toList(), null);
                }
            }
        }
    }

    public synchronized void event(EventEnvelope event) {
        if (closed || Topic.MANDATORY.contains(event.type().topic())) {
            return;
        }
        for (AutomationSpec spec : specs.values()) {
            if (enabled(spec) && spec.trigger() instanceof AutomationSpec.OnEvent trigger
                    && trigger.event() == event.type() && trigger.match().entrySet().stream()
                            .allMatch(entry -> entry.getValue().equals(String.valueOf(event.payload().get(entry.getKey()))))) {
                launch(spec, List.of(event.payload()), null);
            }
        }
    }

    private Optional<String> launch(AutomationSpec spec, List<Map<String, Object>> events, String resumedTask) {
        if (closed || !enabled(spec) || lockdown.getAsBoolean() || active.containsKey(spec.id())) {
            log.info("automação {}: disparo pulado (desativada, lockdown ou execução em curso)", spec.id());
            return Optional.empty();
        }
        String first = resumedTask == null ? workflow.prepare(spec, events.getFirst()) : resumedTask;
        if (resumedTask == null) {
            states.automationFired(spec.id(), clock.instant());
        }
        Thread worker = Thread.ofVirtual().name("automation-" + spec.id()).unstarted(() -> {
            String taskId = first;
            try {
                for (int i = 0; i < events.size(); i++) {
                    if (i > 0) {
                        synchronized (this) {
                            if (closed || lockdown.getAsBoolean() || !enabled(spec)) {
                                break;
                            }
                            taskId = workflow.prepare(spec, events.get(i));
                            activeTasks.put(spec.id(), taskId);
                            states.automationFired(spec.id(), clock.instant());
                        }
                    }
                    WorkflowEngine.Outcome outcome = workflow.resume(spec, taskId);
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    int failures = states.automationFinished(spec.id(), outcome.ok());
                    if (spec.once() || failures >= Math.min(20, spec.limits().maxFailures())) {
                        String reason = spec.once() ? "disparo único concluído" : failures + " falhas seguidas";
                        states.automationDisabled(spec.id(), true, reason);
                        notifier.notify(spec, "Automação desativada", reason, "warning");
                        break;
                    }
                }
            } catch (RuntimeException e) {
                tasks.taskState(taskId, "blocked", "erro interno: " + e.getMessage());
                log.warn("automação {} interrompida: {}", spec.id(), e.toString());
            } finally {
                synchronized (this) {
                    active.remove(spec.id());
                    activeTasks.remove(spec.id());
                }
            }
        });
        active.put(spec.id(), worker);
        activeTasks.put(spec.id(), first);
        worker.start();
        return Optional.of(first);
    }

    private AutomationSpec require(String id) {
        return Optional.ofNullable(specs.get(id)).orElseThrow(() -> new IllegalArgumentException("automação desconhecida: " + id));
    }

    private void validate(AutomationSpec spec) {
        for (AutomationSpec.Step step : spec.steps()) {
            if (step.agent() != null && agents.find(step.agent()).isEmpty()) {
                throw new IllegalArgumentException("agente desconhecido: " + step.agent());
            }
            if (step.tool() != null) {
                if (tools.baseRisk(step.tool()).orElse(RiskLevel.RED) != RiskLevel.GREEN) {
                    throw new IllegalArgumentException("automação só aceita ferramenta GREEN existente: " + step.tool());
                }
                // Argumentos interpolados serão descritos e autorizados novamente em cada chamada.
                if (!step.args().toString().contains("{{")) {
                    try {
                        if (tools.describe(step.tool(), step.args()).orElseThrow().baseRisk() != RiskLevel.GREEN) {
                            throw new IllegalArgumentException("argumentos elevam o risco de " + step.tool());
                        }
                    } catch (zordon.core.tools.ToolException e) {
                        throw new IllegalArgumentException(e.getMessage(), e);
                    }
                }
            }
        }
    }

    private static String summary(AutomationSpec spec) {
        // A tela mostra também argumentos, mensagens, condições, retries e limites antes de aprovar.
        return spec.name() + " — " + spec.trigger().summary() + "\n\n" + spec.toToml();
    }

    @Override
    public void close() {
        List<Thread> workers;
        synchronized (this) {
            closed = true;
            if (timer != null) {
                timer.shutdownNow();
            }
            if (subscription != null) {
                bus.unsubscribe(subscription);
            }
            workers = List.copyOf(active.values());
            workers.forEach(Thread::interrupt);
        }
        for (Thread worker : workers) {
            try {
                worker.join(java.time.Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static final class DurationSupport {
        static boolean fresh(Instant sample, Instant now) {
            return !sample.equals(Instant.EPOCH) && !sample.isAfter(now)
                    && java.time.Duration.between(sample, now).getSeconds() <= 10;
        }
    }
}
