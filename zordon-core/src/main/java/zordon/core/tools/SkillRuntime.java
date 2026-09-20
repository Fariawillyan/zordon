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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventType;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Principal;
import zordon.api.trace.Spec;
import zordon.core.event.ZordonEventBus;
import zordon.security.AuditLog;
import zordon.security.Gatekeeper;
import zordon.security.PermissionEngine;

/**
 * Registro e execução das ferramentas (SPEC-016). Toda chamada passa pelo
 * {@link Gatekeeper}: não existe atalho do nome da ferramenta até o efeito.
 */
@Spec("SPEC-016")
public final class SkillRuntime {

    private static final Logger log = LoggerFactory.getLogger(SkillRuntime.class);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    /** Turnos que já leram conteúdo: dali em diante, rede e exportação são RED (SPEC-019 CA-4). */
    private final Set<String> tainted = ConcurrentHashMap.newKeySet();
    /** Oferecidas sempre ao modelo; as demais entram pelas palavras do pedido. */
    static final List<String> PINNED = List.of("system.metrics", "fs.list", "fs.read", "app.open");
    public static final int MAX_OFFERED = 12;
    private final Gatekeeper gatekeeper;
    private final ZordonEventBus bus;
    private final BooleanSupplier lockdown;

    public SkillRuntime(Gatekeeper gatekeeper, ZordonEventBus bus, BooleanSupplier lockdown) {
        this.gatekeeper = Objects.requireNonNull(gatekeeper, "gatekeeper");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.lockdown = Objects.requireNonNull(lockdown, "lockdown");
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
        void call(ActionDescriptor action, Principal actor, String turnId, zordon.api.security.Decision decision,
                Set<zordon.api.security.Effect> declared, boolean tainted);

        void result(ActionDescriptor action, String turnId, String text, String status);
    }

    private volatile CallObserver observer = new CallObserver() {
        @Override
        public void call(ActionDescriptor action, Principal actor, String turnId,
                zordon.api.security.Decision decision, Set<zordon.api.security.Effect> declared, boolean tainted) {
        }

        @Override
        public void result(ActionDescriptor action, String turnId, String text, String status) {
        }
    };

    private volatile java.util.function.Predicate<Principal> breakerOpen = actor -> false;

    /** O disjuntor do sujeito (SPEC-027): aberto, o motor nega tudo dele. */
    public SkillRuntime breakerBy(java.util.function.Predicate<Principal> open) {
        this.breakerOpen = Objects.requireNonNull(open, "open");
        return this;
    }

    /** Liga a defesa: cada chamada e cada resultado viram observação (SPEC-026). */
    public SkillRuntime observedBy(CallObserver watcher) {
        this.observer = Objects.requireNonNull(watcher, "watcher");
        return this;
    }

    private volatile java.util.function.BiConsumer<zordon.api.security.ActionDescriptor, String> completed =
            (action, turnId) -> { };

    /** Avisado de cada ferramenta que terminou OK: o trabalho vira memória (SPEC-021 CA-7). */
    public SkillRuntime onCompleted(java.util.function.BiConsumer<zordon.api.security.ActionDescriptor, String> observer) {
        this.completed = Objects.requireNonNull(observer, "observer");
        return this;
    }

    /** Tira uma ferramenta do registro (servidor MCP que caiu, SPEC-020 CA-5). */
    public SkillRuntime unregister(String name) {
        tools.remove(name);
        return this;
    }

    /** Para {@code tools.list} (SPEC-016 CA-6). */
    public List<Map<String, Object>> list() {
        return tools.values().stream().sorted(java.util.Comparator.comparing(Tool::name)).map(tool -> {
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
            zordon.api.security.RiskLevel ceiling, java.util.function.Consumer<zordon.api.security.Decision> decided) {
        return call(name, args, actor, turnId, new PermissionEngine.PolicyContext(lockdown.getAsBoolean(),
                breakerOpen.test(actor), tainted(turnId), true, false, Set.of(), ceiling), decided)
                .thenApply(Outcome::result);
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
        return call(name, args, actor, runId, new PermissionEngine.PolicyContext(lockdown.getAsBoolean(), false,
                tainted(runId), false, false, approvedScope, zordon.api.security.RiskLevel.GREEN), decision -> { });
    }

    /** O risco que a ação teria, sem executar nada: a validação de uma proposta de automação. */
    public java.util.Optional<ActionDescriptor> describe(String name, Map<String, Object> args) throws ToolException {
        Tool tool = tools.get(name);
        return tool == null ? java.util.Optional.empty() : java.util.Optional.of(tool.describe(args));
    }

    public java.util.Optional<zordon.api.security.RiskLevel> baseRisk(String name) {
        return java.util.Optional.ofNullable(tools.get(name)).map(Tool::baseRisk);
    }

    private CompletableFuture<Outcome> call(String name, Map<String, Object> args, Principal actor, String turnId,
            PermissionEngine.PolicyContext ctx, java.util.function.Consumer<zordon.api.security.Decision> decided) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return CompletableFuture.completedFuture(new Outcome("failed",
                    ToolResult.of("Não conheço a ferramenta " + name + ".")));
        }
        ActionDescriptor action;
        try {
            action = tool.describe(args);
        } catch (ToolException e) {
            return CompletableFuture.completedFuture(new Outcome("failed",
                    ToolResult.of(capitalized(e.getMessage()) + ".")));
        }
        return gatekeeper.authorize(action, actor, ctx, turnId).thenApplyAsync(permit -> {
            try {
                decided.accept(permit.decision());
            } catch (RuntimeException e) {
                log.debug("observador de decisão falhou: {}", e.getMessage());
            }
            bus.publish(EventType.TOOL_CALLED, Map.of("callId", permit.callId(), "tool", name,
                    "risk", permit.decision().risk().wire(), "decision", permit.decision().wire()));
            try {
                observer.call(action, actor, turnId, permit.decision(), tool.effects(), ctx.tainted());
            } catch (RuntimeException e) {
                log.debug("observador de chamada falhou: {}", e.getMessage());
            }
            return switch (permit) {
                case Gatekeeper.Permit.Refused refused -> {
                    bus.publish(EventType.TOOL_RESULT, Map.of("callId", permit.callId(), "tool", name,
                            "status", "refused", "durationMs", 0));
                    yield new Outcome("refused", ToolResult.of("Não fiz: " + refused.decision().reason() + "."));
                }
                case Gatekeeper.Permit.Granted granted -> execute(tool, granted, args, turnId);
            };
        }, runnable -> Thread.ofVirtual().name("zordon-tool-" + name).start(runnable));
    }

    private Outcome execute(Tool tool, Gatekeeper.Permit.Granted permit, Map<String, Object> args, String turnId) {
        long started = System.nanoTime();
        ToolResult result;
        AuditLog.Status status;
        String error = null;
        try {
            result = tool.run(permit, args, turnId);
            status = AuditLog.Status.OK;
        } catch (ToolException e) {
            result = ToolResult.of(capitalized(e.getMessage()) + ".");
            status = AuditLog.Status.FAILED;
            error = e.getMessage();
        } catch (Exception e) {
            log.warn("{} falhou: {}", tool.name(), e.toString());
            result = ToolResult.of("Não consegui: " + e.getMessage() + ".");
            status = AuditLog.Status.FAILED;
            error = e.toString();
        }
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        if (status == AuditLog.Status.OK && turnId != null
                && tool.effects().contains(zordon.api.security.Effect.READ_FS)) {
            tainted.add(turnId);
        }
        gatekeeper.complete(permit, status, took, result.text(), error);
        if (status == AuditLog.Status.OK) {
            try {
                completed.accept(permit.action(), turnId);
            } catch (RuntimeException e) {
                log.debug("observador de ferramenta falhou: {}", e.getMessage());
            }
        }
        bus.publish(EventType.TOOL_RESULT, Map.of("callId", permit.callId(), "tool", tool.name(),
                "status", status.wire(), "durationMs", took.toMillis()));
        try {
            observer.result(permit.action(), turnId, result.text(), status.wire());
        } catch (RuntimeException e) {
            log.debug("observador de resultado falhou: {}", e.getMessage());
        }
        return new Outcome(status == AuditLog.Status.OK ? "ok" : "failed", result);
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
    public List<Offered> offer(String userText, java.util.function.Predicate<String> allows, List<String> pinned,
            zordon.api.security.RiskLevel ceiling) {
        java.util.function.Predicate<Tool> fits = tool -> tool.modelVisible() && allows.test(tool.name())
                && (ceiling == null || tool.baseRisk().compareTo(ceiling) <= 0);
        String text = userText == null ? "" : java.text.Normalizer.normalize(userText.toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        Set<String> words = new java.util.HashSet<>(List.of(text.split("[^a-z0-9]+")));
        words.removeIf(word -> word.length() < 4);
        List<Tool> chosen = new java.util.ArrayList<>();
        (pinned.isEmpty() ? PINNED : pinned).stream().map(tools::get).filter(Objects::nonNull).filter(fits)
                .forEach(chosen::add);
        tools.values().stream().filter(fits).filter(tool -> !chosen.contains(tool))
                .sorted(java.util.Comparator.comparingLong((Tool tool) -> -matches(tool, words))
                        .thenComparing(Tool::name))
                .filter(tool -> matches(tool, words) > 0)
                .limit(MAX_OFFERED - chosen.size())
                .forEach(chosen::add);
        return chosen.stream().map(tool -> new Offered(wireName(tool.name()), tool.name(), tool.description()
                + " Risco: " + tool.baseRisk().wire() + ".", tool.inputSchema())).toList();
    }

    private static long matches(Tool tool, Set<String> words) {
        String haystack = java.text.Normalizer.normalize((tool.name() + " " + tool.description())
                .toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return words.stream().filter(haystack::contains).count();
    }

    /** Uma ferramenta oferecida: o nome que o modelo vê, o nome real e o esquema. */
    public record Offered(String wireName, String name, String description, Map<String, Object> inputSchema) {}

    public static String wireName(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** O nome real de um nome saneado, entre as ferramentas registradas. */
    public java.util.Optional<String> resolveWireName(String wire) {
        // Só as que o modelo pode ver: um nome adivinhado não chega a uma ferramenta do usuário.
        return tools.values().stream().filter(Tool::modelVisible).map(Tool::name)
                .filter(name -> name.equals(wire) || wireName(name).equals(wire)).findFirst();
    }

    private static String capitalized(String text) {
        return text == null || text.isEmpty() ? "falhou" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
