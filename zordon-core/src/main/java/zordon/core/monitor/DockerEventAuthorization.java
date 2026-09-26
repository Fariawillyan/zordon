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
package zordon.core.monitor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;
import zordon.security.Gatekeeper;
import zordon.security.PermissionEngine;

/** A ação fixa, só de leitura, que o monitor do Docker pede ao Gatekeeper. */
final class DockerEventAuthorization {

    private DockerEventAuthorization() {}

    static Gatekeeper.Permit authorize(Gatekeeper gatekeeper, List<String> command) throws Exception {
        ActionDescriptor action = new ActionDescriptor("monitor.docker", Map.of(), RiskLevel.GREEN,
                Set.of(Effect.SPAWN_PROCESS), List.of(), 1, command,
                "Acompanhar os eventos dos containers");
        return gatekeeper.authorize(action, new Principal("system:monitor", RequestOrigin.UI, false),
                PermissionEngine.PolicyContext.interactive(), null).get(70, TimeUnit.SECONDS);
    }
}
