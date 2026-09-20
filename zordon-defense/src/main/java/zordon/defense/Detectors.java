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
package zordon.defense;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import zordon.api.security.Effect;
import zordon.api.trace.Spec;
import zordon.security.Redactor;

/** Os detectores do Anel 1 e do Anel 3 (SPEC-026). Cada um é uma regra escrita, sem modelo. */
@Spec("SPEC-026")
public final class Detectors {

    /** Trecho de evidência: curto e redigido; nenhum segredo entra num achado. */
    static final int EVIDENCE = 200;

    /**
     * Imperativos dirigidos ao assistente. Documentação sobre segurança fala *sobre*
     * injeção sem mandar o assistente fazer nada, e por isso não casa.
     */
    static final List<Pattern> INJECTION = List.of(
            Pattern.compile("ignore (as |todas as |suas |estas )?(instrucoes|regras|orientacoes)"),
            Pattern.compile("ignore (all |any |your |the )?(previous |prior |above )?instructions"),
            Pattern.compile("desconsidere (as |todas as )?(instrucoes|regras)"),
            Pattern.compile("esqueca (as |tudo o que|o que)"),
            Pattern.compile("voce (deve|precisa|tem que) (executar|rodar|enviar|baixar|apagar|abrir)"),
            Pattern.compile("you (must|should) (run|execute|send|download|delete)"),
            Pattern.compile("(nao|sem) (conte|avise|avisar|mostrar|mostre) (isso |nada )?(ao|para o) usuario"),
            Pattern.compile("do not (tell|inform|show) the user"),
            Pattern.compile("execute o (seguinte|comando abaixo)"),
            Pattern.compile("(envie|mande|poste|exfiltre|upload)[^.]{0,40}(para|to) https?://"),
            Pattern.compile("new instructions:"),
            Pattern.compile("</?(system|instru(c|ç)(o|õ)es)>"));

    /** Caminhos cuja escrita é tentativa de tocar a política ou a auditoria. */
    static final List<String> PROTECTED = List.of("/.zordon/state/", "/.zordon/audit", "audit.db", "policy",
            "/.zordon/quarantine/");

    private Detectors() {}

    public static List<Detector> ring1(Redactor redactor, java.util.function.LongSupplier nanos) {
        return List.of(promptInjection(redactor), capabilityViolation(), permissionProbing(nanos), exfiltration(),
                agentLoop(), policyTamper(), mcpDrift(), outputAnomaly(redactor));
    }

    /** O Anel 2 (SPEC-027): as observações já vêm prontas do {@code HostWatch}. */
    public static List<Detector> ring2() {
        return List.of(named("host.credential-access", "host.credential-access", 1.0),
                named("host.persistence", "host.persistence", 0.7),
                named("host.new-listener", "host.new-listener", 0.4));
    }

    public static List<Detector> integrity() {
        return List.of(named("integrity.audit-chain", "integrity.audit", 1.0),
                named("integrity.self", "integrity.self", 1.0),
                named("integrity.config-tamper", "integrity.config", 0.7));
    }

    /** Conteúdo de terceiro mandando o assistente fazer algo (Defesa §4, Anel 1). */
    static Detector promptInjection(Redactor redactor) {
        return new Detector() {
            @Override public String id() { return "ai.prompt-injection"; }

            @Override
            public List<Signal> inspect(Observation observation) {
                if (!"tool.result".equals(observation.kind()) || observation.text() == null) {
                    return List.of();
                }
                String plain = plain(observation.text());
                List<String> hits = new ArrayList<>();
                for (Pattern pattern : INJECTION) {
                    var matcher = pattern.matcher(plain);
                    if (matcher.find()) {
                        hits.add(matcher.group());
                    }
                }
                if (hits.isEmpty()) {
                    return List.of();
                }
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("tool", observation.tool());
                evidence.put("padroes", hits);
                evidence.put("trecho", snippet(redactor, observation.text(), hits.getFirst()));
                // Um padrão já conta; dois ou mais pesam mais, porque texto citando um ataque casa um só.
                return List.of(new Signal(id(), "conteúdo com instrução dirigida ao assistente",
                        hits.size() >= 2 ? 0.8 : 0.5, evidence, observation.ts()));
            }
        };
    }

    /** A ação tem efeito que a ferramenta não declarou: a declaração é contrato. */
    static Detector capabilityViolation() {
        return new Detector() {
            @Override public String id() { return "ai.capability-violation"; }

            @Override
            public List<Signal> inspect(Observation observation) {
                if (!"tool.call".equals(observation.kind())) {
                    return List.of();
                }
                List<Effect> extra = observation.actionEffects().stream()
                        .filter(effect -> !observation.declaredEffects().contains(effect)).toList();
                if (extra.isEmpty()) {
                    return List.of();
                }
                return List.of(new Signal(id(), "efeito não declarado", 1.0,
                        Map.of("tool", String.valueOf(observation.tool()), "efeitos", extra.stream().map(Enum::name)
                                .toList()), observation.ts()));
            }
        };
    }

    /** Três negações do mesmo ator em 60 s: alguém tateando o que passa. */
    static Detector permissionProbing(java.util.function.LongSupplier nanos) {
        Map<String, ArrayDeque<Long>> denials = new LinkedHashMap<>();
        Duration window = Duration.ofSeconds(60);
        return new Detector() {
            @Override public String id() { return "ai.permission-probing"; }

            @Override
            public synchronized List<Signal> inspect(Observation observation) {
                if (!"tool.call".equals(observation.kind()) || !"deny".equals(observation.decision())) {
                    return List.of();
                }
                String actor = observation.actor() == null ? "?" : observation.actor();
                ArrayDeque<Long> recent = denials.computeIfAbsent(actor, key -> new ArrayDeque<>());
                long now = nanos.getAsLong();
                recent.addLast(now);
                while (!recent.isEmpty() && now - recent.peekFirst() > window.toNanos()) {
                    recent.removeFirst();
                }
                if (recent.size() < 3) {
                    return List.of();
                }
                recent.clear();
                return List.of(new Signal(id(), "negações em sequência", 0.7,
                        Map.of("ator", actor, "ultima", String.valueOf(observation.reason())), observation.ts()));
            }
        };
    }

    /** Saída de dados depois de ler conteúdo externo: o padrão de exfiltração. */
    static Detector exfiltration() {
        return new Detector() {
            @Override public String id() { return "ai.exfiltration"; }

            @Override
            public List<Signal> inspect(Observation observation) {
                boolean exporting = observation.actionEffects().contains(Effect.NETWORK)
                        || observation.actionEffects().contains(Effect.EXPORT_DATA);
                if (!"tool.call".equals(observation.kind()) || !observation.tainted() || !exporting) {
                    return List.of();
                }
                return List.of(new Signal(id(), "saída de dados em turno contaminado", 0.8,
                        Map.of("tool", String.valueOf(observation.tool()), "decisao",
                                String.valueOf(observation.decision())), observation.ts()));
            }
        };
    }

    /** Cinco chamadas idênticas seguidas: repetição improdutiva. */
    static Detector agentLoop() {
        Map<String, String> last = new LinkedHashMap<>();
        Map<String, Integer> repeats = new LinkedHashMap<>();
        return new Detector() {
            @Override public String id() { return "ai.agent-loop"; }

            @Override
            public synchronized List<Signal> inspect(Observation observation) {
                if (!"tool.call".equals(observation.kind())) {
                    return List.of();
                }
                String actor = observation.actor() == null ? "?" : observation.actor();
                String signature = observation.tool() + " " + new java.util.TreeMap<>(observation.data());
                int count = signature.equals(last.get(actor)) ? repeats.getOrDefault(actor, 1) + 1 : 1;
                last.put(actor, signature);
                repeats.put(actor, count);
                if (count < 5) {
                    return List.of();
                }
                repeats.put(actor, 0);
                return List.of(new Signal(id(), "mesma chamada repetida", 0.3,
                        Map.of("tool", String.valueOf(observation.tool()), "vezes", count), observation.ts()));
            }
        };
    }

    /** Tentativa de escrever em política, estado ou auditoria. */
    static Detector policyTamper() {
        return new Detector() {
            @Override public String id() { return "ai.policy-tamper"; }

            @Override
            public List<Signal> inspect(Observation observation) {
                if (!"tool.call".equals(observation.kind())) {
                    return List.of();
                }
                boolean kernel = observation.actionEffects().contains(Effect.MODIFY_TRUST_KERNEL);
                String paths = String.valueOf(observation.data().getOrDefault("paths", ""));
                boolean writing = observation.actionEffects().contains(Effect.WRITE_FS)
                        || observation.actionEffects().contains(Effect.QUARANTINE_FS)
                        || observation.actionEffects().contains(Effect.MODIFY_SYSTEM);
                boolean protectedPath = writing && PROTECTED.stream().anyMatch(paths::contains);
                if (!kernel && !protectedPath) {
                    return List.of();
                }
                return List.of(new Signal(id(), "tentativa de tocar política ou auditoria", 1.0,
                        Map.of("tool", String.valueOf(observation.tool()), "caminhos", paths, "decisao",
                                String.valueOf(observation.decision())), observation.ts()));
            }
        };
    }

    /** Servidor MCP que muda de superfície entre conexões (SPEC-020). */
    static Detector mcpDrift() {
        return named("ai.mcp-drift", "mcp.drift", 0.8);
    }

    /** A resposta do modelo levou um segredo reconhecido. */
    static Detector outputAnomaly(Redactor redactor) {
        return new Detector() {
            @Override public String id() { return "ai.output-anomaly"; }

            @Override
            public List<Signal> inspect(Observation observation) {
                if (!"model.output".equals(observation.kind()) || observation.text() == null
                        || !redactor.containsSecret(observation.text())) {
                    return List.of();
                }
                return List.of(new Signal(id(), "segredo na resposta do modelo", 0.7,
                        Map.of("trecho", redactor.redact(observation.text().substring(0,
                                Math.min(EVIDENCE, observation.text().length())))), observation.ts()));
            }
        };
    }

    /** Detector de um tipo de observação que já vem pronta de quem a produziu. */
    static Detector named(String id, String kind, double weight) {
        return new Detector() {
            @Override public String id() { return id; }

            @Override
            public List<Signal> inspect(Observation observation) {
                if (!kind.equals(observation.kind())) {
                    return List.of();
                }
                Map<String, Object> evidence = new LinkedHashMap<>(observation.data());
                if (observation.reason() != null) {
                    evidence.put("motivo", observation.reason());
                }
                return List.of(new Signal(id, kind, weight, evidence, observation.ts()));
            }
        };
    }

    static String plain(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ");
    }

    /** O trecho em volta do primeiro padrão, redigido. */
    static String snippet(Redactor redactor, String text, String hit) {
        String plain = plain(text);
        int at = Math.max(0, plain.indexOf(hit) - 40);
        String window = text.substring(Math.min(at, Math.max(0, text.length() - 1)),
                Math.min(text.length(), at + EVIDENCE));
        return redactor.redact(window.replaceAll("\\s+", " ").strip());
    }
}
