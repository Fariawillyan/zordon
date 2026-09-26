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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;
import zordon.core.event.ZordonEventBus;
import zordon.security.Gatekeeper;

/**
 * Registro e execução das ferramentas (SPEC-016). Toda chamada passa pelo
 * {@link Gatekeeper}: não existe atalho do nome da ferramenta até o efeito.
 *
 * <p>O caminho até o Gatekeeper fica em {@link ToolCalls}; a execução, em
 * {@link ToolExecution}; a escolha do que oferecer ao modelo, em {@link ToolOffer}.
 */
@Spec("SPEC-016")
public final class SkillRuntime {

    /** Oferecidas sempre ao modelo; as demais entram pelas palavras do pedido. */
    static final List<String> PINNED = List.of("system.metrics", "fs.list", "fs.read", "app.open");
    public static final int MAX_OFFERED = 12;

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    /** Turnos que já leram conteúdo: dali em diante, rede e exportação são RED (SPEC-019 CA-4). */
    private final Set<String> tainted = ConcurrentHashMap.newKeySet();
    private final ToolCalls calls;

    public SkillRuntime(Gatekeeper gatekeeper, ZordonEventBus bus, BooleanSupplier lockdown) {
        this.calls = new ToolCalls(Objects.requireNonNull(gatekeeper, "gatekeeper"),
                Objects.requireNonNull(bus, "bus"), Objects.requireNonNull(lockdown, "lockdown"), tools, tainted);
    }

    public SkillRuntime register(Tool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalArgumentException("ferramenta duplicada: " + tool.name());
        }
        return this;
    }

    /**
     * Quem observa cada chamada para a defesa (SPEC-026). Fica atrás de uma interface
     * para as ferramentas não conhecerem o módulo de detecção.
     */
    public interface CallObserver {
        void call(ActionDescriptor action, Principal actor, String turnId, Decision decision, Set<Effect> declared,
                boolean tainted);

        void result(ActionDescriptor action, String turnId, String text, String status);
    }

    /** O disjuntor do sujeito (SPEC-027): aberto, o motor nega tudo dele. */
    public SkillRuntime breakerBy(Predicate<Principal> open) {
        calls.breakerBy(open);
        return this;
    }

    /** Liga a defesa: cada chamada e cada resultado viram observação (SPEC-026). */
    public SkillRuntime observedBy(CallObserver watcher) {
        calls.wiring().observers().watch(watcher);
        return this;
    }

    /** Avisado de cada ferramenta que terminou OK: o trabalho vira memória (SPEC-021 CA-7). */
    public SkillRuntime onCompleted(BiConsumer<ActionDescriptor, String> observer) {
        calls.wiring().observers().onCompleted(observer);
        return this;
    }

    /** Tira uma ferramenta do registro (servidor MCP que caiu, SPEC-020 CA-5). */
    public SkillRuntime unregister(String name) {
        tools.remove(name);
        return this;
    }

    /** Para {@code tools.list} (SPEC-016 CA-6). */
    public List<Map<String, Object>> list() {
        return tools.values().stream().sorted(Comparator.comparing(Tool::name)).map(tool -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", tool.name());
            row.put("description", tool.description());
            row.put("risk", tool.baseRisk().wire());
            row.put("effects", tool.effects().stream().map(Enum::name).sorted().toList());
            return row;
        }).toList();
    }

    /** Invoca e devolve o texto a dizer: o resultado, a recusa ou o motivo do erro. */
    public CompletableFuture<ToolResult> invoke(String name, Map<String, Object> args, Principal actor, String turnId) {
        return invoke(name, args, actor, turnId, null, decision -> { });
    }

    /**
     * Com o teto do agente e quem acompanha as decisões (o disjuntor da execução,
     * SPEC-022). O teto vai para o motor: acima dele, nega sem perguntar.
     */
    public CompletableFuture<ToolResult> invoke(String name, Map<String, Object> args, Principal actor, String turnId,
            RiskLevel ceiling, Consumer<Decision> decided) {
        return calls.forUser(name, args, actor, turnId, ceiling, decided).thenApply(Outcome::result);
    }

    /** Como uma chamada terminou: {@code ok}, {@code refused} ou {@code failed}, e o que ela disse. */
    public record Outcome(String status, ToolResult result) {
        public boolean ok() {
            return "ok".equals(status);
        }
    }

    /**
     * Para um passo de automação (SPEC-025): sem usuário presente (a ação sobe um
     * nível) e com o escopo aprovado. RED nunca roda; o motor garante.
     */
    public CompletableFuture<Outcome> invokeForAutomation(String name, Map<String, Object> args, Principal actor,
            String runId, Set<String> approvedScope) {
        return calls.forAutomation(name, args, actor, runId, approvedScope);
    }

    /** O risco que a ação teria, sem executar nada: a validação de uma proposta de automação. */
    public Optional<ActionDescriptor> describe(String name, Map<String, Object> args) throws ToolException {
        Tool tool = tools.get(name);
        return tool == null ? Optional.empty() : Optional.of(tool.describe(args));
    }

    public Optional<RiskLevel> baseRisk(String name) {
        return Optional.ofNullable(tools.get(name)).map(Tool::baseRisk);
    }

    /** O turno terminou: a marca de contaminação dele sai. */
    public void endTurn(String turnId) {
        if (turnId != null) {
            tainted.remove(turnId);
        }
    }

    /** Conteúdo externo entrou no turno por outro caminho (o resultado de um sub-agente). */
    public void taint(String turnId) {
        if (turnId != null) {
            tainted.add(turnId);
        }
    }

    public boolean tainted(String turnId) {
        return turnId != null && tainted.contains(turnId);
    }

    /**
     * As ferramentas para o modelo (SPEC-019 CA-3): as fixas e as que casam com
     * as palavras do pedido, no máximo 12. Nome saneado para os protocolos que só
     * aceitam {@code [a-zA-Z0-9_-]}: {@code fs.read} vira {@code fs_read}.
     */
    public List<Offered> offer(String userText) {
        return offer(userText, tool -> true, PINNED, null);
    }

    /**
     * Para um agente (SPEC-022): só o que o escopo permite e o que cabe no teto; as
     * fixadas do agente no lugar das padrão.
     */
    public List<Offered> offer(String userText, Predicate<String> allows, List<String> pinned, RiskLevel ceiling) {
        return ToolOffer.offer(tools, userText, allows, pinned, ceiling);
    }

    /** Uma ferramenta oferecida: o nome que o modelo vê, o nome real e o esquema. */
    public record Offered(String wireName, String name, String description, Map<String, Object> inputSchema) {}

    public static String wireName(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** O nome real de um nome saneado, entre as ferramentas registradas. */
    public Optional<String> resolveWireName(String wire) {
        // Só as que o modelo pode ver: um nome adivinhado não chega a uma ferramenta do usuário.
        return tools.values().stream().filter(Tool::modelVisible).map(Tool::name)
                .filter(name -> name.equals(wire) || wireName(name).equals(wire)).findFirst();
    }

    static String capitalized(String text) {
        return text == null || text.isEmpty() ? "falhou" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
