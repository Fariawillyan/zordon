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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RiskLevel;
import zordon.api.security.ZPath;
import zordon.api.trace.Spec;
import zordon.security.Gatekeeper;
import zordon.security.PathPolicy;

/**
 * Leitura, listagem e escrita de arquivos (SPEC-016). Todo caminho é resolvido
 * para o destino canônico antes de ser classificado (SPEC-014 CA-4).
 */
@Spec("SPEC-016")
public final class FileTools {

    public static final int MAX_LISTED = 200;
    public static final int MAX_READ = 256 * 1024;

    private FileTools() {}

    /** Resolve o argumento {@code path} a partir de {@code base} (o {@code $HOME}). */
    static ZPath resolve(PathPolicy paths, ZPath base, Map<String, Object> args) throws ToolException {
        String raw = Tool.text(args, "path");
        try {
            return paths.canonical(raw, base);
        } catch (IOException | IllegalArgumentException e) {
            throw new ToolException("não consegui entender o caminho " + raw);
        }
    }

    public static Tool list(PathPolicy paths, ZPath base) {
        return new Tool() {
            @Override public String name() { return "fs.list"; }
            @Override public String description() { return "Lista uma pasta (até " + MAX_LISTED + " entradas)."; }
            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(Effect.READ_FS); }
            @Override public Map<String, Object> inputSchema() {
                return Tool.schema("path", "caminho absoluto, com ~ ou relativo ao home (Windows: D:/pasta)");
            }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                ZPath path = resolve(paths, base, args);
                return new ActionDescriptor(name(), args, baseRisk(), effects(), List.of(path), 1, List.of(),
                        "Listar a pasta " + path);
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                Path dir = Path.of(permit.action().touchedPaths().getFirst().toWsl());
                if (!Files.isDirectory(dir)) {
                    throw new ToolException("não existe a pasta " + permit.action().touchedPaths().getFirst());
                }
                List<String> names;
                long total;
                try (Stream<Path> entries = Files.list(dir)) {
                    List<Path> all = entries.sorted().toList();
                    total = all.size();
                    names = all.stream().limit(MAX_LISTED)
                            .map(p -> p.getFileName() + (Files.isDirectory(p) ? "/" : "")).toList();
                }
                String text = total == 0 ? "A pasta está vazia."
                        : total + (total == 1 ? " item: " : " itens: ") + String.join(", ", names.stream().limit(12).toList())
                                + (total > 12 ? "…" : "") + ".";
                return new ToolResult(text, Map.of("entries", names, "total", total));
            }
        };
    }

    public static Tool read(PathPolicy paths, ZPath base) {
        return new Tool() {
            @Override public String name() { return "fs.read"; }
            @Override public String description() { return "Lê um arquivo de texto (até 256 KB)."; }
            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(Effect.READ_FS); }
            @Override public Map<String, Object> inputSchema() {
                return Tool.schema("path", "caminho absoluto, com ~ ou relativo ao home (Windows: D:/pasta)");
            }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                ZPath path = resolve(paths, base, args);
                return new ActionDescriptor(name(), args, baseRisk(), effects(), List.of(path), 1, List.of(),
                        "Ler o arquivo " + path);
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                Path file = Path.of(permit.action().touchedPaths().getFirst().toWsl());
                if (!Files.isRegularFile(file)) {
                    throw new ToolException("não existe o arquivo " + permit.action().touchedPaths().getFirst());
                }
                long size = Files.size(file);
                byte[] bytes;
                try (var in = Files.newInputStream(file)) {
                    bytes = in.readNBytes(MAX_READ);
                }
                String content = new String(bytes, StandardCharsets.UTF_8);
                boolean cut = size > MAX_READ;
                String text = cut ? "Li os primeiros 256 KB de " + size / 1024 + " KB; o resto ficou de fora."
                        : "Li " + content.lines().count() + " linhas.";
                return new ToolResult(text, Map.of("content", content, "truncated", cut, "bytes", size));
            }
        };
    }

    /** Escreve um arquivo novo. Não sobrescreve: substituir conteúdo é destruir o anterior (ADR-0015). */
    public static Tool write(PathPolicy paths, ZPath base) {
        return new Tool() {
            @Override public String name() { return "fs.write"; }
            @Override public String description() { return "Cria um arquivo de texto numa área de trabalho; não sobrescreve."; }
            @Override public RiskLevel baseRisk() { return RiskLevel.YELLOW; }
            @Override public Set<Effect> effects() { return Set.of(Effect.WRITE_FS); }

            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of(
                        "path", Map.of("type", "string", "description", "onde criar o arquivo (não sobrescreve)"),
                        "content", Map.of("type", "string", "description", "o texto do arquivo")),
                        "required", List.of("path", "content"));
            }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                ZPath path = resolve(paths, base, args);
                String content = args.get("content") instanceof String text ? text : "";
                if (Files.exists(Path.of(path.toWsl()))) {
                    throw new ToolException("o arquivo " + path + " já existe e eu não sobrescrevo");
                }
                return new ActionDescriptor(name(), Map.of("path", path.toString(), "chars", content.length()),
                        baseRisk(), effects(), List.of(path), 1, List.of(),
                        "Criar o arquivo " + path + " com " + content.length() + " caracteres");
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                Path file = Path.of(permit.action().touchedPaths().getFirst().toWsl());
                String content = args.get("content") instanceof String text ? text : "";
                Files.createDirectories(file.getParent());
                Path part = file.resolveSibling(file.getFileName() + ".zordon-part");
                Files.writeString(part, content, StandardCharsets.UTF_8);
                // Sem REPLACE_EXISTING: se o arquivo surgiu entre a pergunta e a escrita, falha em vez de destruir.
                Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
                return ToolResult.of("Criei " + permit.action().touchedPaths().getFirst() + ".");
            }
        };
    }
}
