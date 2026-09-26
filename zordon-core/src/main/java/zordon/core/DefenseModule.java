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

import java.nio.file.Path;
import java.time.Clock;
import zordon.api.event.EventType;
import zordon.api.security.Principal;
import zordon.core.defense.DefenseService;
import zordon.core.defense.IntegrityWatch;
import zordon.core.mcp.McpManager;
import zordon.defense.CircuitBreakers;
import zordon.defense.HostWatch;

/** Detecção, integridade, o Anel 2 e os servidores MCP que ela vigia (SPEC-020, SPEC-026, SPEC-027). */
final class DefenseModule {

    private final DefenseService defense;
    private final IntegrityWatch integrity;
    private final CircuitBreakers breakers;
    private final HostWatch hostWatch;
    private final McpManager mcp;

    DefenseModule(CoreBase base, TrustModule trust, ToolModule tools, MemoryModule memory) {
        this.defense = new DefenseService(
                new DefenseService.Outlets(memory.database().findings(), trust.notifications(), base.bus()),
                base.redactor(), Clock.systemUTC(), System::nanoTime);
        this.integrity = IntegrityWatch.of(base.config().home(), defense::integrityChanged);
        this.breakers = new CircuitBreakers(Clock.systemUTC());
        this.hostWatch = new HostWatch(Path.of("/proc"),
                Path.of(base.environment().getOrDefault("HOME", System.getProperty("user.home"))), Clock.systemUTC(),
                defense::observe);
        tools.tools().observedBy(defense).breakerBy(actor -> breakers.isOpen(subjectOf(actor)));
        base.turns().hooks().onCompleted(done -> {
            memory.distiller().completed(done);
            defense.modelOutput(done.turn().value(), done.answer());
        });
        this.mcp = new McpManager(
                McpManager.load(base.config().home().resolve("config.toml")),
                new McpManager.Deps(trust.gatekeeper(), trust.runner(), tools.tools(), trust.notifications()),
                new McpManager.Dirs(base.config().home().resolve("state"), base.config().home().resolve("mcp-work")),
                Clock.systemUTC(), System::nanoTime).onAlert(alert -> {
                    base.bus().publish(EventType.SYSTEM_ALERT, alert);
                    if ("drift".equals(alert.get("event"))) {
                        defense.mcpDrift(String.valueOf(alert.get("server")), String.valueOf(alert.get("detail")));
                    }
                });
    }

    DefenseService defense() {
        return defense;
    }

    IntegrityWatch integrity() {
        return integrity;
    }

    CircuitBreakers breakers() {
        return breakers;
    }

    HostWatch hostWatch() {
        return hostWatch;
    }

    McpManager mcp() {
        return mcp;
    }

    /** O sujeito do disjuntor a partir do ator: {@code agent:x}, {@code automation:y} ou o ator. */
    private static String subjectOf(Principal actor) {
        String name = actor.actor();
        if (name.startsWith("agent:")) {
            return "agent:" + name.substring("agent:".length());
        }
        if (name.startsWith("automation:")) {
            return "automation:" + name.substring("automation:".length());
        }
        return "actor:" + name;
    }
}
