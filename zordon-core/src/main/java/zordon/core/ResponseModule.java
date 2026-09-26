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

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import zordon.core.defense.DefenseEngine;

/** A resposta da defesa (SPEC-027): o que um achado pode fazer — isolar um MCP, cancelar um agente, lockdown. */
final class ResponseModule {

    private final DefenseModule defense;
    private final DefenseEngine engine;

    ResponseModule(CoreBase base, TrustModule trust, MemoryModule memory, DefenseModule defense, AgentModule agents) {
        this.defense = defense;
        this.engine = new DefenseEngine(defense.breakers(), memory.database().securityEvents(), trust.notifications(),
                base.bus(), new DefenseEngine.Actions() {
                    @Override
                    public boolean isolateMcp(String server) {
                        return defense.mcp().isolate(server);
                    }

                    @Override
                    public boolean cancelAgent(String agent) {
                        return agents.runs().cancelAgent(agent) >= 0;
                    }

                    @Override
                    public void lockdown(String reason) {
                        trust.lockdown().enter(reason, "defense");
                    }
                }, Clock.systemUTC());
        defense.defense().respondWith(engine::respond);
    }

    DefenseEngine engine() {
        return engine;
    }

    /** O diagnóstico da defesa inteira: achados, integridade, o Anel 2 e os disjuntores. */
    Map<String, Object> diagnostics() {
        Map<String, Object> out = new LinkedHashMap<>(defense.defense().diagnostics());
        out.put("integrity", defense.integrity().status());
        out.put("host", defense.hostWatch().status());
        out.put("breakers", engine.breakers());
        return out;
    }
}
