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
package zordon.core.memory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;
import zordon.core.tools.Tool;
import zordon.core.tools.ToolException;
import zordon.core.tools.ToolResult;
import zordon.memory.Fact;
import zordon.memory.FactKind;
import zordon.memory.MemoryHit;
import zordon.memory.MemoryStore;
import zordon.memory.NewFact;
import zordon.memory.RecallQuery;
import zordon.memory.TimeWindows;
import zordon.security.Gatekeeper;

/** {@code memory.remember} (só do usuário) e {@code memory.search} (também do modelo), SPEC-021. */
@Spec("SPEC-021")
public final class MemoryTools {

    /** Confiança do que o usuário mandou lembrar com as próprias palavras. */
    static final double USER_CONFIDENCE = 0.95;

    public static Tool remember(MemoryStore store, Clock clock, Consumer<Fact> written) {
        return new Tool() {
            @Override public String name() { return "memory.remember"; }
            @Override public String description() { return "Guarda algo que o usuário pediu para lembrar."; }
            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(); }
            @Override public boolean modelVisible() { return false; }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                String content = Tool.text(args, "content");
                if (content.length() > NewFact.MAX_CONTENT) {
                    throw new ToolException("isso é longo demais para lembrar; resuma em até 300 caracteres");
                }
                return new ActionDescriptor(name(), args, RiskLevel.GREEN, Set.of(), List.of(), 0, List.of(),
                        "Lembrar: " + content);
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                return run(permit, args, null);
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args, String turnId)
                    throws Exception {
                String content = Tool.text(args, "content");
                FactKind kind = kindOf(content);
                Fact fact = store.remember(new NewFact(kind, subjectOf(kind, content), content, USER_CONFIDENCE,
                        clock.instant(), null, turnId == null ? permit.callId() : turnId, "user", false));
                written.accept(fact);
                return ToolResult.of("Anotado: " + content + ".");
            }
        };
    }

    public static Tool search(MemoryStore store, Clock clock, ZoneId zone) {
        return new Tool() {
            @Override public String name() { return "memory.search"; }

            @Override
            public String description() {
                return "Procura na memória do Zordon: preferências, projetos, decisões e o que foi feito em cada dia."
                        + " Use when=hoje|ontem|semana para uma janela de tempo.";
            }

            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(); }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of(
                        "query", Map.of("type", "string", "description", "o que procurar"),
                        "when", Map.of("type", "string", "enum", List.of("hoje", "ontem", "semana"),
                                "description", "janela de tempo, opcional")),
                        "required", List.of("query"));
            }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                Tool.text(args, "query");
                return new ActionDescriptor(name(), args, RiskLevel.GREEN, Set.of(), List.of(), 0, List.of(),
                        "Procurar na memória");
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                String query = Tool.text(args, "query");
                String when = args.get("when") instanceof String given ? given : "";
                var window = TimeWindows.in(when.equals("semana") ? "esta semana" : when + " " + query, zone,
                        clock.instant());
                List<MemoryHit> hits = store.search(new RecallQuery(query, Set.of(),
                        window.map(TimeWindows.Window::since).orElse(null),
                        window.map(TimeWindows.Window::until).orElse(null), 8));
                if (hits.isEmpty()) {
                    return ToolResult.of("Nada na memória sobre isso"
                            + window.map(found -> " (" + found.label() + ")").orElse("") + ".");
                }
                store.touched(hits.stream().map(hit -> hit.fact().id()).toList());
                StringBuilder text = new StringBuilder();
                hits.forEach(hit -> text.append(line(hit.fact(), zone)).append('\n'));
                return ToolResult.of(text.toString().strip());
            }
        };
    }

    /** Uma linha legível para o modelo e para a voz: data, tipo, assunto e o fato. */
    static String line(Fact fact, ZoneId zone) {
        return "- " + LocalDate.ofInstant(fact.observedAt(), zone) + " · " + label(fact.kind()) + " · "
                + fact.subject() + ": " + fact.content();
    }

    static String label(FactKind kind) {
        return switch (kind) {
            case PREFERENCE -> "preferência";
            case PROJECT -> "projeto";
            case ENTITY -> "fato";
            case EVENT -> "evento";
            case PROCEDURE -> "procedimento";
        };
    }

    /** O tipo pelas palavras (SPEC-021 §3). */
    static FactKind kindOf(String content) {
        String plain = java.text.Normalizer.normalize(content.toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        if (plain.matches(".*\\b(prefiro|prefere|gosto|gosta|nao gosto|odeio|detesto|quero sempre|sempre quero)\\b.*")) {
            return FactKind.PREFERENCE;
        }
        if (plain.matches(".*\\bpara .+,? (rode|roda|use|usa|execute|faca|digite)\\b.*")) {
            return FactKind.PROCEDURE;
        }
        if (plain.matches(".*\\bprojeto\\b.*")) {
            return FactKind.PROJECT;
        }
        return FactKind.ENTITY;
    }

    /** O assunto: o nome do projeto, ou as primeiras palavras que dizem algo. */
    static String subjectOf(FactKind kind, String content) {
        java.util.regex.Matcher project = java.util.regex.Pattern.compile("(?i)\\bprojeto\\s+([\\p{L}\\p{N}_.-]+)")
                .matcher(content);
        if (kind == FactKind.PROJECT && project.find()) {
            return "projeto " + project.group(1);
        }
        List<String> words = java.util.Arrays.stream(content.split("[^\\p{L}\\p{N}_.-]+"))
                .filter(word -> word.length() > 2)
                .filter(word -> !Set.of("que", "para", "com", "sem", "uma", "meu", "minha", "eu", "prefiro", "gosto",
                        "sempre", "nunca", "não", "nao").contains(word.toLowerCase(java.util.Locale.ROOT)))
                .limit(4).toList();
        String subject = words.isEmpty() ? content : String.join(" ", words);
        return subject.length() > NewFact.MAX_SUBJECT ? subject.substring(0, NewFact.MAX_SUBJECT) : subject;
    }

    private MemoryTools() {}
}
