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

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import zordon.api.trace.Spec;
import zordon.core.agents.AgentRegistry;
import zordon.core.event.EventSubscription;
import zordon.core.event.QueuePolicy;
import zordon.core.event.ZordonEventBus;
import zordon.core.monitor.SystemSampler;
import zordon.core.tools.SkillRuntime;
import zordon.memory.AutomationStateStore;
import zordon.memory.TaskStore;

/**
 * Aprovação, gatilhos e exclusão mútua das execuções da SPEC-025.
 *
 * <p>Esta classe é o lock e o ciclo de vida; as automações conhecidas moram em
 * {@link AutomationCatalog}, o que dispara em {@link AutomationTriggers} e as
 * execuções em {@link AutomationRuns}.
 */
@Spec("SPEC-025")
public final class AutomationEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AutomationEngine.class);

    /** Onde os estados dos gatilhos e as tarefas geradas são gravados. */
    public record Stores(AutomationStateStore states, TaskStore tasks) {}

    /** O que executa uma automação: ferramentas, agentes, o fluxo e o aviso. */
    public record Engines(SkillRuntime tools, AgentRegistry agents, WorkflowEngine workflow,
            WorkflowEngine.Notifier notifier) {}

    /** O ambiente em volta: barramento, trava de lockdown e as métricas do sistema. */
    public record Env(ZordonEventBus bus, BooleanSupplier lockdown, Supplier<SystemSampler.Snapshot> metrics) {}

    private final Env env;
    private final AutomationCatalog catalog;
    private final AutomationRuns runs;
    private final AutomationTriggers triggers;
    private ScheduledExecutorService timer;
    private EventSubscription subscription;

    public AutomationEngine(Path directory, Stores stores, Engines engines, Env env, Clock clock, LongSupplier nanos) {
        this.env = env;
        this.catalog = new AutomationCatalog(directory, stores, engines);
        this.runs = new AutomationRuns(catalog, stores, engines, env, clock, this);
        this.triggers = new AutomationTriggers(catalog, runs, env.metrics(), clock, nanos);
    }

    public synchronized void start() {
        reload();
        subscription = env.bus().subscribe("automations", Topic.ALL, QueuePolicy.dropOldest(256), this::event);
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
        if (!runs.closed()) {
            catalog.reload();
        }
    }

    public synchronized Map<String, Object> propose(Map<String, Object> raw) {
        return catalog.propose(raw);
    }

    public synchronized String approve(String proposalId) {
        return catalog.approve(proposalId);
    }

    public synchronized boolean reject(String proposalId) {
        return catalog.reject(proposalId);
    }

    public synchronized boolean enable(String id, boolean enabled) {
        return catalog.enable(id, enabled);
    }

    public synchronized boolean hasConditions() {
        return catalog.hasConditions();
    }

    public synchronized Map<String, Object> list() {
        return catalog.list(runs::running);
    }

    public synchronized Map<String, Object> diagnostics() {
        Map<String, Object> out = new LinkedHashMap<>(catalog.counts());
        out.put("running", runs.count());
        out.put("tokensToday", runs.tokensToday());
        return Map.copyOf(out);
    }

    public synchronized Optional<String> fire(String id, Map<String, Object> event) {
        return runs.launch(catalog.require(id), List.of(event), null);
    }

    public synchronized boolean resume(String taskId) {
        return runs.resume(taskId);
    }

    public synchronized boolean cancel(String taskId) {
        return runs.cancel(taskId);
    }

    /** Chamável nos testes sem dormir: relógio e amostra são injetados. */
    public synchronized void tick() {
        if (!runs.closed()) {
            triggers.tick();
        }
    }

    public synchronized void event(EventEnvelope event) {
        if (!runs.closed()) {
            triggers.event(event);
        }
    }

    @Override
    public void close() {
        List<Thread> workers;
        synchronized (this) {
            workers = runs.close();
            if (timer != null) {
                timer.shutdownNow();
            }
            if (subscription != null) {
                env.bus().unsubscribe(subscription);
            }
        }
        AutomationRuns.await(workers);
    }
}
