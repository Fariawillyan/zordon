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
package zordon.core.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RiskLevel;
import zordon.api.security.ZPath;
import zordon.api.trace.Spec;
import zordon.security.Gatekeeper;
import zordon.security.PathPolicy;
import zordon.security.ProcessRunner;

/**
 * Ferramentas que rodam programas do catálogo pelo {@link ProcessRunner}
 * (SPEC-016): {@code git.status} e {@code build.run}. O comando é montado aqui,
 * nunca recebido pronto.
 */
@Spec("SPEC-016")
public final class ProcessTools {

    private ProcessTools() {}

    /** {@code docker ps}: os containers rodando (SPEC-022, UC4). */
    public static Tool dockerPs(ZPath base, ProcessRunner runner) {
        return new Tool() {
            @Override public String name() { return "docker.ps"; }
            @Override public String description() { return "Lista os containers Docker rodando: nome, estado e imagem."; }
            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(Effect.SPAWN_PROCESS); }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) {
                return new ActionDescriptor(name(), args, baseRisk(), effects(), List.of(), 1,
                        List.of("docker", "ps", "--all", "--format", "{{.Names}}\t{{.Status}}\t{{.Image}}"),
                        "Listar os containers Docker");
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                ProcessRunner.Result result = runner.run(permit, Path.of(base.toWsl()), Duration.ofSeconds(20));
                if (result.exitCode() != 0) {
                    throw new ToolException("o docker não respondeu: " + firstLine(result.stderr()));
                }
                List<String> lines = result.stdout().lines().filter(line -> !line.isBlank()).toList();
                if (lines.isEmpty()) {
                    return ToolResult.of("Nenhum container.");
                }
                StringBuilder text = new StringBuilder(lines.size() + (lines.size() == 1 ? " container:" : " containers:"));
                lines.forEach(line -> text.append("\n- ").append(line.replace('\t', ' ')));
                return ToolResult.of(text.toString());
            }
        };
    }

    /** {@code docker logs --tail}: o fim do log de um container. É conteúdo externo: contamina o turno. */
    public static Tool dockerLogs(ZPath base, ProcessRunner runner) {
        return new Tool() {
            @Override public String name() { return "docker.logs"; }

            @Override
            public String description() {
                return "Mostra as últimas linhas do log de um container Docker (tail até 500).";
            }

            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(Effect.SPAWN_PROCESS, Effect.READ_FS); }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of(
                        "container", Map.of("type", "string", "description", "nome do container"),
                        "tail", Map.of("type", "integer", "description", "quantas linhas do fim, até 500")),
                        "required", List.of("container"));
            }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                String container = Tool.text(args, "container");
                if (!container.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")) {
                    throw new ToolException("nome de container inválido: " + container);
                }
                int tail = args.get("tail") instanceof Number number ? number.intValue() : 200;
                tail = Math.max(1, Math.min(tail, 500));
                return new ActionDescriptor(name(), Map.of("container", container, "tail", tail), baseRisk(), effects(),
                        List.of(), 1, List.of("docker", "logs", "--tail", String.valueOf(tail), "--timestamps", container),
                        "Ler as últimas " + tail + " linhas do log de " + container);
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                ProcessRunner.Result result = runner.run(permit, Path.of(base.toWsl()), Duration.ofSeconds(30));
                if (result.exitCode() != 0) {
                    throw new ToolException("o docker não leu o log: " + firstLine(result.stderr()));
                }
                // O docker manda a saída de erro do container para o stderr: as duas contam.
                String log = (result.stdout() + (result.stderr().isBlank() ? "" : "\n" + result.stderr())).strip();
                return ToolResult.of(log.isEmpty() ? "O log está vazio." : log
                        + (result.truncated() ? "\n[cortado em 64 KB]" : ""));
            }
        };
    }

    private static String firstLine(String text) {
        return text == null || text.isBlank() ? "sem detalhe" : text.strip().lines().findFirst().orElse("");
    }

    public static Tool gitStatus(PathPolicy paths, ZPath base, ProcessRunner runner) {
        return new Tool() {
            @Override public String name() { return "git.status"; }
            @Override public String description() { return "Mostra o estado de um repositório Git."; }
            @Override public Map<String, Object> inputSchema() { return Tool.schema("path", "pasta do repositório"); }
            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(Effect.SPAWN_PROCESS); }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                ZPath repo = FileTools.resolve(paths, base, args);
                return new ActionDescriptor(name(), args, baseRisk(), effects(), List.of(repo), 1,
                        List.of("git", "status", "--short", "--branch"), "Ver o estado do repositório " + repo);
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                Path repo = Path.of(permit.action().touchedPaths().getFirst().toWsl());
                ProcessRunner.Result result = runner.run(permit, repo, Duration.ofSeconds(20));
                if (result.exitCode() != 0) {
                    throw new ToolException("o git não reconheceu " + permit.action().touchedPaths().getFirst()
                            + " como repositório");
                }
                List<String> lines = result.stdout().lines().toList();
                String branch = lines.isEmpty() ? "?" : lines.getFirst().replaceFirst("^## ", "");
                int changes = Math.max(0, lines.size() - 1);
                String text = changes == 0 ? "Ramo " + branch + ", sem alterações."
                        : "Ramo " + branch + ", " + changes + (changes == 1 ? " alteração." : " alterações.");
                return new ToolResult(text, Map.of("output", result.stdout()));
            }
        };
    }

    public static Tool build(PathPolicy paths, ZPath base, ProcessRunner runner) {
        return new Tool() {
            @Override public String name() { return "build.run"; }
            @Override public String description() { return "Roda o build de um projeto (gradle ou npm)."; }
            @Override public Map<String, Object> inputSchema() { return Tool.schema("path", "pasta do projeto"); }
            @Override public RiskLevel baseRisk() { return RiskLevel.YELLOW; }
            @Override public Set<Effect> effects() { return Set.of(Effect.SPAWN_PROCESS, Effect.WRITE_FS); }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                ZPath project = FileTools.resolve(paths, base, args);
                Path dir = Path.of(project.toWsl());
                String kind = args.get("kind") instanceof String given ? given.toLowerCase(Locale.ROOT)
                        : Files.exists(dir.resolve("package.json")) ? "npm" : "gradle";
                List<String> command = switch (kind) {
                    case "npm" -> List.of("npm", "run", "build");
                    case "gradle" -> List.of("gradle", "build");
                    default -> throw new ToolException("não sei rodar o build de " + kind);
                };
                return new ActionDescriptor(name(), Map.of("path", project.toString(), "kind", kind), baseRisk(),
                        effects(), List.of(project), 1, command, "Rodar " + String.join(" ", command) + " em " + project);
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                Path project = Path.of(permit.action().touchedPaths().getFirst().toWsl());
                ProcessRunner.Result result = runner.run(permit, project, Duration.ofMinutes(10));
                List<String> tail = result.stdout().lines().toList();
                String last = String.join("\n", tail.subList(Math.max(0, tail.size() - 5), tail.size()));
                if (result.timedOut()) {
                    throw new ToolException("o build passou de 10 minutos e foi encerrado");
                }
                return new ToolResult(result.exitCode() == 0 ? "O build passou." : "O build falhou (código "
                        + result.exitCode() + ").", Map.of("tail", last, "exitCode", result.exitCode()));
            }
        };
    }

    /** Carga, memória e disco do WSL, lidos de {@code /proc} e do sistema de arquivos. */
    public static Tool metrics() {
        return new Tool() {
            @Override public String name() { return "system.metrics"; }
            @Override public String description() { return "Mostra carga, memória e disco do WSL."; }
            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(); }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) {
                return new ActionDescriptor(name(), Map.of(), baseRisk(), effects(), List.of(), 0, List.of(),
                        "Ler as métricas do sistema");
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                String load = Files.readString(Path.of("/proc/loadavg")).split(" ")[0];
                long total = 0;
                long available = 0;
                for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                    if (line.startsWith("MemTotal:")) {
                        total = kilobytes(line);
                    } else if (line.startsWith("MemAvailable:")) {
                        available = kilobytes(line);
                    }
                }
                var store = Files.getFileStore(Path.of("/"));
                double usedDisk = (store.getTotalSpace() - store.getUsableSpace()) / 1e9;
                double totalDisk = store.getTotalSpace() / 1e9;
                String text = String.format(Locale.ROOT, "Carga %s; memória %.1f de %.1f GB em uso; disco %.0f de %.0f GB.",
                        load, (total - available) / 1e6, total / 1e6, usedDisk, totalDisk).replace('.', ',')
                        .replaceFirst(",$", ".");
                return new ToolResult(text, Map.of("load", load, "memTotalKb", total, "memAvailableKb", available));
            }

            private static long kilobytes(String line) {
                return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        };
    }
}
