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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import zordon.api.security.RiskLevel;

/** Os servidores MCP declarados no {@code config.toml}. */
final class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);

    private McpConfig() {}

    /** {@code [[mcp.server]]} do {@code config.toml}. Entrada inválida é ignorada com aviso. */
    static List<McpManager.Server> load(Path configToml) {
        if (!Files.exists(configToml)) {
            return List.of();
        }
        try {
            TomlParseResult toml = Toml.parse(configToml);
            TomlArray array = toml.getArray("mcp.server");
            if (array == null) {
                return List.of();
            }
            List<McpManager.Server> out = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                TomlTable table = array.getTable(i);
                String name = table.getString("name");
                TomlArray command = table.getArray("command");
                if (name == null || !name.matches("[a-z0-9-]{1,32}") || command == null || command.isEmpty()) {
                    log.warn("mcp.server #{} ignorado: precisa de name (a-z, 0-9, -) e command", i + 1);
                    continue;
                }
                List<String> argv = new ArrayList<>();
                for (int k = 0; k < command.size(); k++) {
                    argv.add(command.getString(k));
                }
                String floor = table.getString("risk_floor");
                Boolean autostart = table.getBoolean("autostart");
                out.add(new McpManager.Server(name, List.copyOf(argv),
                        floor == null ? RiskLevel.YELLOW : RiskLevel.valueOf(floor.toUpperCase(Locale.ROOT)),
                        autostart == null || autostart));
            }
            return List.copyOf(out);
        } catch (IOException | RuntimeException e) {
            log.warn("configuração de MCP ilegível: {}", e.getMessage());
            return List.of();
        }
    }
}
