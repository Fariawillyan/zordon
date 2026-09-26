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
package zordon.core.mcp;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import zordon.core.tools.SkillRuntime;

/** As ferramentas de um servidor no registro: entram quando a superfície é aceita e saem quando ele cai. */
final class McpToolset {

    private final SkillRuntime tools;
    private final McpSurfaces surfaces;
    private final Clock clock;
    private volatile Duration callTimeout = McpTool.CALL_TIMEOUT;

    McpToolset(SkillRuntime tools, McpSurfaces surfaces, Clock clock) {
        this.tools = tools;
        this.surfaces = surfaces;
        this.clock = clock;
    }

    void callTimeout(Duration timeout) {
        this.callTimeout = timeout;
    }

    /** Registra as ferramentas da superfície; as vistas há menos de uma semana entram como novas. */
    void add(McpConnection connection, List<Map<String, Object>> surface) throws IOException {
        Map<String, String> seen = surfaces.firstSeen();
        Instant now = clock.instant();
        for (Map<String, Object> declared : surface) {
            String key = connection.server.name() + "." + declared.get("name");
            Instant first = seen.containsKey(key) ? Instant.parse(seen.get(key)) : now;
            seen.putIfAbsent(key, now.toString());
            boolean fresh = Duration.between(first, now).compareTo(McpManager.NEW_TOOL) < 0;
            McpTool tool = new McpTool(connection.server.name(), declared, connection.server.floor(), fresh,
                    new McpTool.Wiring(() -> connection.client, connection.breaker, callTimeout));
            tools.register(tool);
            connection.registered.add(tool.name());
        }
        surfaces.seen(seen);
    }

    void remove(McpConnection connection) {
        connection.registered.forEach(tools::unregister);
        connection.registered.clear();
    }
}
