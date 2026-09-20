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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;

/**
 * Recusa o que nunca deve rodar, antes de qualquer classificação
 * (docs/security/model.md §4, SPEC-014 CA-5).
 *
 * <ul>
 *   <li>Sem shell: não há string para injetar, porque não há shell para interpretá-la.
 *   <li>Programas só do catálogo, por nome; fora dele é RED com o caminho visível.
 *   <li>Subcomandos perigosos por programa, numa tabela explícita.
 * </ul>
 */
@Spec("SPEC-014")
public final class CommandValidator {

    /** O resultado: negado com o motivo, ou aceito com o piso de risco e o programa resolvido. */
    public sealed interface Validation {
        record Denied(String reason) implements Validation {}

        record Accepted(RiskLevel floor, String program, String note) implements Validation {}
    }

    /** Interpretadores de comando: nunca são chamados, nem com confirmação. */
    static final Set<String> SHELLS = Set.of("sh", "bash", "zsh", "dash", "fish", "ksh", "csh", "tcsh",
            "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe", "wsl", "wsl.exe");

    /** Sequências de argumentos que tornam o comando RED; {@code *} no fim casa por prefixo. */
    static final Map<String, List<List<String>>> DANGEROUS = Map.of(
            "git", List.of(List.of("reset", "--hard"), List.of("push", "--force*"), List.of("push", "-f"),
                    List.of("clean", "-fdx"), List.of("clean", "-xdf"), List.of("filter-branch"),
                    List.of("gc", "--prune=now")),
            "docker", List.of(List.of("system", "prune"), List.of("volume", "rm"), List.of("rm", "-f")),
            "npm", List.of(List.of("publish")),
            "pnpm", List.of(List.of("publish")),
            "mvn", List.of(List.of("deploy"), List.of("release*")),
            "gradle", List.of(List.of("--init-script*"), List.of("publish*"), List.of("release*")));

    private final Map<String, String> catalog;

    /** @param catalog nome → caminho absoluto, resolvido na inicialização */
    public CommandValidator(Map<String, String> catalog) {
        this.catalog = Map.copyOf(Objects.requireNonNull(catalog, "catalog"));
    }

    public Validation validate(List<String> command) {
        if (command.isEmpty()) {
            return new Validation.Denied("comando vazio");
        }
        String program = command.getFirst();
        String name = baseName(program);
        if (SHELLS.contains(name)) {
            return new Validation.Denied("sem shell: " + name + " nunca é chamado pelo Zordon");
        }
        List<String> args = command.subList(1, command.size());
        String resolved = catalog.get(name);
        if (resolved == null) {
            return new Validation.Accepted(RiskLevel.RED, program,
                    "programa fora do catálogo: " + program);
        }
        for (List<String> pattern : DANGEROUS.getOrDefault(name, List.of())) {
            if (containsInOrder(args, pattern)) {
                return new Validation.Accepted(RiskLevel.RED, resolved, name + " " + String.join(" ", pattern));
            }
        }
        return new Validation.Accepted(RiskLevel.GREEN, resolved, null);
    }

    private static String baseName(String program) {
        String slashed = program.replace('\\', '/');
        String name = slashed.substring(slashed.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") && !SHELLS.contains(name) ? name.substring(0, name.length() - 4) : name;
    }

    /** Cada token do padrão aparece nos argumentos, nesta ordem (não precisam ser vizinhos). */
    private static boolean containsInOrder(List<String> args, List<String> pattern) {
        int next = 0;
        for (String arg : args) {
            String token = pattern.get(next);
            boolean hit = token.endsWith("*") ? arg.startsWith(token.substring(0, token.length() - 1))
                    : arg.equals(token);
            if (hit && ++next == pattern.size()) {
                return true;
            }
        }
        return false;
    }
}
