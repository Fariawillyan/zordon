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
package zordon.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import zordon.api.trace.Spec;

/**
 * A política de segurança do {@code config.toml}, lida uma vez na inicialização
 * e imutável em execução (docs/security/model.md §4). Sem arquivo ou sem seção,
 * valem os padrões: nenhuma área de escrita, leitura só no {@code $HOME}.
 *
 * <pre>
 * [paths]
 * workspaces = ["~/dev", "D:/projetos"]
 * readable   = ["~", "D:/"]
 * forbidden  = ["**&#47;segredos/**"]      # soma aos proibidos padrão
 *
 * [programs]
 * allow = ["git", "gradle", "docker"]     # resolvidos pelo PATH, na inicialização
 * </pre>
 */
@Spec("SPEC-014")
public record SecuritySettings(PathPolicy paths, CommandValidator validator, Map<String, String> catalog) {

    /** Programas do catálogo quando o arquivo não diz nada. */
    public static final List<String> DEFAULT_PROGRAMS =
            List.of("git", "docker", "gradle", "mvn", "npm", "pnpm", "java", "node", "python3", "claude", "npx", "uvx");

    public static SecuritySettings load(Path configToml, String home, String pathVariable) throws IOException {
        List<String> workspaces = List.of();
        List<String> readable = List.of("~");
        List<String> extraForbidden = List.of();
        List<String> programs = DEFAULT_PROGRAMS;
        if (Files.exists(configToml)) {
            TomlParseResult toml = Toml.parse(configToml);
            if (toml.hasErrors()) {
                throw new IOException("config.toml inválido: " + toml.errors().getFirst());
            }
            workspaces = strings(toml.getArray("paths.workspaces"), workspaces);
            readable = strings(toml.getArray("paths.readable"), readable);
            extraForbidden = strings(toml.getArray("paths.forbidden"), extraForbidden);
            programs = strings(toml.getArray("programs.allow"), programs);
        }
        PathPolicy defaults = PathPolicy.defaults(home, workspaces, readable);
        PathPolicy paths = extraForbidden.isEmpty() ? defaults : defaults.withForbidden(extraForbidden);
        Map<String, String> catalog = new LinkedHashMap<>();
        for (String program : programs) {
            resolve(program, pathVariable).ifPresent(found -> catalog.put(program, found));
        }
        return new SecuritySettings(paths, new CommandValidator(catalog), Map.copyOf(catalog));
    }

    private static java.util.Optional<String> resolve(String program, String pathVariable) {
        if (pathVariable == null) {
            return java.util.Optional.empty();
        }
        for (String dir : pathVariable.split(":")) {
            if (dir.isBlank() || dir.startsWith("/mnt/")) {
                continue;   // o catálogo é do lado do núcleo; binário do Windows passa pelo host (SPEC-016)
            }
            Path candidate = Path.of(dir, program);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return java.util.Optional.of(candidate.toAbsolutePath().toString());
            }
        }
        return java.util.Optional.empty();
    }

    private static List<String> strings(TomlArray array, List<String> fallback) {
        if (array == null) {
            return fallback;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            out.add(array.getString(i));
        }
        return List.copyOf(out);
    }
}
