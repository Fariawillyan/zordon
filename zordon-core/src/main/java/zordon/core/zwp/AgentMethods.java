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
package zordon.core.zwp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import zordon.api.security.RequestOrigin;
import zordon.api.trace.Spec;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.agents.AgentProfile;
import zordon.core.agents.AgentRegistry;
import zordon.core.agents.AgentService;

/** {@code agent.list}, {@code agent.run}, {@code agent.cancel} e {@code agent.runs} (SPEC-022). */
@Spec("SPEC-022")
public final class AgentMethods {

    private final AgentRegistry registry;
    private final AgentService service;

    public AgentMethods(AgentRegistry registry, AgentService service) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.service = Objects.requireNonNull(service, "service");
    }

    public void registerOn(ZwpServer server) {
        server.register("agent.list", (session, params) -> {
            AgentRegistry.Snapshot snapshot = registry.snapshot();
            return Map.of("agents", snapshot.agents().stream().map(AgentMethods::wire).toList(),
                    "invalid", snapshot.invalid().stream()
                            .map(bad -> Map.of("file", bad.file(), "reason", bad.reason())).toList());
        }).register("agent.run", (session, params) -> {
            if (!(params.get("agent") instanceof String agent) || agent.isBlank()
                    || !(params.get("task") instanceof String task) || task.isBlank()) {
                throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "agent e task são obrigatórios");
            }
            return Map.of("runId", service.start(agent, task, RequestOrigin.UI).orElseThrow(() ->
                    new ZwpMethodException(ZwpErrorKind.ERR_NOT_FOUND, "não conheço o agente " + agent)));
        }).register("agent.cancel", (session, params) -> {
            if (!(params.get("runId") instanceof String runId) || runId.isBlank()) {
                throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "runId é obrigatório");
            }
            return Map.of("cancelled", service.cancel(runId));
        }).register("agent.runs", (session, params) -> Map.of("runs", service.runs()));
    }

    static Map<String, Object> wire(AgentProfile agent) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", agent.id());
        out.put("name", agent.name());
        out.put("description", agent.description());
        out.put("ceiling", agent.ceiling().wire());
        out.put("role", agent.role().name().toLowerCase(java.util.Locale.ROOT));
        out.put("source", agent.source());
        return out;
    }
}
