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
package zordon.core.agents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import zordon.api.security.RequestOrigin;
import zordon.api.trace.Spec;

/** {@code agent.run} em segundo plano, com cancelamento (SPEC-022). */
@Spec("SPEC-022")
public final class AgentService {

    /** Uma execução em curso ou terminada nesta sessão do núcleo. */
    public static final class Run {
        final String runId;
        final String agent;
        final String task;
        final TurnScope scope;
        volatile boolean cancelled;
        volatile String state = "running";

        Run(String runId, String agent, String task, TurnScope scope) {
            this.runId = runId;
            this.agent = agent;
            this.task = task;
            this.scope = scope;
        }

        Map<String, Object> wire() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runId", runId);
            out.put("agent", agent);
            out.put("task", task.length() > 200 ? task.substring(0, 200) + "…" : task);
            out.put("state", state);
            out.put("steps", scope.meter().steps());
            return out;
        }
    }

    private final AgentRegistry registry;
    private final AgentRunner runner;
    private final LongSupplier nanos;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    public AgentService(AgentRegistry registry, AgentRunner runner, LongSupplier nanos) {
        this.registry = registry;
        this.runner = runner;
        this.nanos = nanos;
    }

    /** Começa e devolve o {@code runId} na hora; o resultado sai em {@code AGENT_FINISHED}. */
    public Optional<String> start(String agentId, String task, RequestOrigin origin) {
        Optional<AgentProfile> agent = registry.find(agentId);
        if (agent.isEmpty()) {
            return Optional.empty();
        }
        String runId = "run_" + Long.toHexString(System.nanoTime());
        Run run = new Run(runId, agent.get().id(), task, TurnScope.of(agent.get(), origin, nanos));
        runs.put(runId, run);
        Thread.ofVirtual().name("zordon-agent-" + runId).start(() -> {
            AgentRunner.Result result = runner.run(run.scope, task, runId, null, () -> run.cancelled);
            run.state = result.ok() ? "done" : result.reason();
        });
        return Optional.of(runId);
    }

    public boolean cancel(String runId) {
        Run run = runs.get(runId);
        if (run == null || !"running".equals(run.state)) {
            return false;
        }
        run.cancelled = true;
        return true;
    }

    /** Cancela tudo o que este agente está rodando (SPEC-027: cancelar, nunca matar). */
    public int cancelAgent(String agentId) {
        int cancelled = 0;
        for (Run run : runs.values()) {
            if (run.agent.equals(agentId) && "running".equals(run.state)) {
                run.cancelled = true;
                cancelled++;
            }
        }
        return cancelled;
    }

    public List<Map<String, Object>> runs() {
        return runs.values().stream().map(Run::wire).toList();
    }
}
