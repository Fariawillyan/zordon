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

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * As execuções: no máximo uma por automação, cada uma numa thread, com os
 * disparos acumulados em sequência. Sob o lock do {@link AutomationEngine}; as
 * threads de execução o tomam.
 */
final class AutomationRuns {

    private static final Logger log = LoggerFactory.getLogger(AutomationEngine.class);

    private final AutomationCatalog catalog;
    private final AutomationEngine.Stores stores;
    private final AutomationEngine.Engines engines;
    private final AutomationEngine.Env env;
    private final Clock clock;
    private final Object lock;
    private final Map<String, Thread> active = new LinkedHashMap<>();
    private final Map<String, String> activeTasks = new LinkedHashMap<>();
    private boolean closed;

    AutomationRuns(AutomationCatalog catalog, AutomationEngine.Stores stores, AutomationEngine.Engines engines,
            AutomationEngine.Env env, Clock clock, Object lock) {
        this.catalog = catalog;
        this.stores = stores;
        this.engines = engines;
        this.env = env;
        this.clock = clock;
        this.lock = lock;
    }

    boolean closed() {
        return closed;
    }

    boolean running(String id) {
        return active.containsKey(id);
    }

    int count() {
        return active.size();
    }

    long tokensToday() {
        return engines.workflow().tokensToday();
    }

    boolean resume(String taskId) {
        var task = stores.tasks().task(taskId).orElse(null);
        if (task == null || !"blocked".equals(task.state()) || !task.origin().startsWith("automation:")) {
            return false;
        }
        AutomationSpec original = engines.workflow().definition(task);
        AutomationSpec current = catalog.get(original.id()).orElse(null);
        if (current == null || !catalog.enabled(current)) {
            return false;
        }
        catalog.validate(original);
        return launch(original, List.of(Map.of()), taskId).isPresent();
    }

    boolean cancel(String taskId) {
        var task = stores.tasks().task(taskId).orElse(null);
        if (task == null || !task.origin().startsWith("automation:")
                || Set.of("done", "failed", "cancelled").contains(task.state())) {
            return false;
        }
        stores.tasks().taskState(taskId, "cancelled", "cancelada na tela");
        String id = task.origin().substring("automation:".length());
        if (taskId.equals(activeTasks.get(id)) && active.get(id) != null) {
            active.get(id).interrupt();
        }
        return true;
    }

    /** Disparo único ou falhas demais desativam a automação. {@code true} para o laço. */
    private boolean disableIfDone(AutomationSpec spec, boolean ok) {
        int failures = stores.states().automationFinished(spec.id(), ok);
        if (!spec.once() && failures < Math.min(20, spec.limits().maxFailures())) {
            return false;
        }
        String reason = spec.once() ? "disparo único concluído" : failures + " falhas seguidas";
        stores.states().automationDisabled(spec.id(), true, reason);
        engines.notifier().notify(spec, "Automação desativada", reason, "warning");
        return true;
    }

    Optional<String> launch(AutomationSpec spec, List<Map<String, Object>> events, String resumedTask) {
        if (closed || !catalog.enabled(spec) || env.lockdown().getAsBoolean() || active.containsKey(spec.id())) {
            log.info("automação {}: disparo pulado (desativada, lockdown ou execução em curso)", spec.id());
            return Optional.empty();
        }
        String first = resumedTask == null ? engines.workflow().prepare(spec, events.getFirst()) : resumedTask;
        if (resumedTask == null) {
            stores.states().automationFired(spec.id(), clock.instant());
        }
        Thread worker = Thread.ofVirtual().name("automation-" + spec.id()).unstarted(() -> {
            String taskId = first;
            try {
                for (int i = 0; i < events.size(); i++) {
                    if (i > 0) {
                        synchronized (lock) {
                            if (closed || env.lockdown().getAsBoolean() || !catalog.enabled(spec)) {
                                break;
                            }
                            taskId = engines.workflow().prepare(spec, events.get(i));
                            activeTasks.put(spec.id(), taskId);
                            stores.states().automationFired(spec.id(), clock.instant());
                        }
                    }
                    WorkflowEngine.Outcome outcome = engines.workflow().resume(spec, taskId);
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    if (disableIfDone(spec, outcome.ok())) {
                        break;
                    }
                }
            } catch (RuntimeException e) {
                stores.tasks().taskState(taskId, "blocked", "erro interno: " + e.getMessage());
                log.warn("automação {} interrompida: {}", spec.id(), e.toString());
            } finally {
                synchronized (lock) {
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

    /** Fecha para novos disparos e interrompe as execuções. @return as threads, para esperar fora do lock */
    List<Thread> close() {
        closed = true;
        List<Thread> workers = List.copyOf(active.values());
        workers.forEach(Thread::interrupt);
        return workers;
    }

    /** Espera cada execução terminar, até 5 s cada. Fora do lock. */
    static void await(List<Thread> workers) {
        for (Thread worker : workers) {
            try {
                worker.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
