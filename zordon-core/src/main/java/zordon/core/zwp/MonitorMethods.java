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

import java.util.Map;
import java.util.Objects;
import zordon.api.trace.Spec;
import zordon.core.monitor.DockerEvents;
import zordon.core.monitor.SystemSampler;

/** {@code system.metrics} e {@code monitor.status} (SPEC-024). */
@Spec("SPEC-024")
public final class MonitorMethods {

    private final SystemSampler sampler;
    private final DockerEvents dockerEvents;

    /** @param dockerEvents {@code null} quando o docker não está no catálogo de programas */
    public MonitorMethods(SystemSampler sampler, DockerEvents dockerEvents) {
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.dockerEvents = dockerEvents;
    }

    public void registerOn(ZwpServer server) {
        server.register("system.metrics", (session, params) -> {
            // Quem pergunta acelera a amostragem: em repouso ela roda a 0,2 Hz (SPEC-024).
            sampler.demand();
            return sampler.latest().wire(sampler.rateHz());
        }).register("monitor.status", (session, params) -> status());
    }

    /** O mesmo estado que o diagnóstico mostra. */
    public Map<String, Object> status() {
        return Map.of("sampler", Map.of("rateHz", sampler.rateHz()),
                "docker", dockerEvents == null ? Map.of("state", "unavailable", "reason",
                        "docker fora do catálogo de programas") : dockerEvents.status());
    }
}
