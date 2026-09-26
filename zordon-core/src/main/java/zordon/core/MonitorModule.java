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

import java.util.function.BooleanSupplier;
import zordon.api.event.EventType;
import zordon.core.monitor.DockerEvents;
import zordon.core.monitor.SystemSampler;
import zordon.core.zwp.MonitorMethods;

/** O monitor do sistema e os eventos de container por push (SPEC-024). */
final class MonitorModule {

    private final SystemSampler sampler;
    /** {@code null} quando o docker não está no catálogo de programas. */
    private final DockerEvents dockerEvents;
    private final MonitorMethods methods;

    /** @param conditions se alguma automação ativa depende de condição: é quando a amostragem precisa correr */
    MonitorModule(CoreBase base, TrustModule trust, BooleanSupplier conditions) {
        this.sampler = SystemSampler.system(conditions);
        this.dockerEvents = base.settings().load().catalog().containsKey("docker")
                ? new DockerEvents(trust.gatekeeper(), trust.runner(), base.config().home().resolve("monitor-work"),
                        payload -> base.bus().publish(EventType.CONTAINER_EVENT, payload),
                        DockerEvents.defaultBackoff())
                : null;
        this.methods = new MonitorMethods(sampler, dockerEvents);
    }

    SystemSampler sampler() {
        return sampler;
    }

    MonitorMethods methods() {
        return methods;
    }

    void start() {
        sampler.start();
        if (dockerEvents != null) {
            dockerEvents.start();
        }
    }

    void close() {
        sampler.close();
        if (dockerEvents != null) {
            dockerEvents.close();
        }
    }
}
