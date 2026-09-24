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
package zordon.core.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.ai.AiException;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.AiStream;
import zordon.ai.ModelRole;
import zordon.ai.StopReason;
import zordon.ai.registry.ProviderRegistry;
import zordon.ai.registry.ProviderRegistry.Resolution;
import zordon.ai.registry.ProviderRegistry.Selection;
import zordon.api.SessionId;
import zordon.api.TokenUsage;
import zordon.api.TurnId;
import zordon.api.event.EventType;
import zordon.api.trace.Spec;
import zordon.core.event.ZordonEventBus;

/**
 * Conduz um turno: da entrada do usuário até a resposta completa.
 *
 * <p>Tudo o que acontece no turno vira evento no barramento. A tela de chat, a de
 * logs e a auditoria são todas projeções do mesmo fluxo — o log de atividades não
 * é uma funcionalidade separada
 * ([Orientação a eventos §2](../../../../../docs/architecture/event-driven.md)).
 *
 * <p>Não conhece fornecedor de modelo: pede ao {@link ProviderRegistry} o provider
 * do papel {@code conversation} e, se ele falhar antes de responder, o do papel
 * {@code fallback} (ADR-0026).
 */
@Spec("SPEC-003")
public final class TurnManager {

    private static final Logger log = LoggerFactory.getLogger(TurnManager.class);
    private static final int MAX_OUTPUT_TOKENS = 8_192;
    /** Tetos do laço de ferramentas (Segurança §8, SPEC-019 CA-3). */
    static final int MAX_TOOL_CALLS = 25;
    static final int MAX_STEPS = 15;
    static final String TOOL_LIMIT_NOTICE = "Parei no limite de ferramentas deste turno.";

    /**
     * Falhas que outro provider pode resolver. Pedido inválido e recusa ficam de
     * fora: o mesmo pedido tende a falhar em qualquer lugar, e insistir mascararia
     * o problema.
     */
    private static final Set<AiException.Kind> FALLBACK_ELIGIBLE = Set.of(
            AiException.Kind.UNAVAILABLE,
            AiException.Kind.RATE_LIMITED,
            AiException.Kind.QUOTA_EXHAUSTED,
            AiException.Kind.NO_CREDENTIALS,
            AiException.Kind.TIMEOUT);

    private final ZordonEventBus bus;
    private final ConversationStore conversations;
    private final IntentRouter router;
    private final PromptComposer prompts;
    private final ProviderRegistry providers;
    private final Map<TurnId, RunningTurn> running = new ConcurrentHashMap<>();

    /** De quem a reserva assumiu, e por quê. A reserva nunca é silenciosa. */
    private record Fallback(String fromProvider, String reason) {}

    /** O turno em curso: quem pediu, para quem foi, e com que reserva. */
    private record Exchange(SessionId session, TurnId turn, Intent.Model intent, RunningTurn handle,
            Selection selection, Fallback fallback) {}

    /**
     * Um turno em andamento, cancelável antes mesmo de o stream existir.
     *
     * <p>Sem isto havia uma janela entre o provider começar a transmitir e o turno
     * ser registrado: um cancelamento nesse intervalo se perdia, e o modelo seguia
     * gerando — e cobrando — enquanto a interface dizia que tinha parado. As duas
     * escritas voláteis garantem que, seja qual for a ordem entre anexar o stream e
     * cancelar, um dos lados vê o outro.
     */
    private static final class RunningTurn {
        private volatile AiStream stream;
        private volatile boolean cancelled;
        /** De onde veio o turno ({@code voice} ou {@code text}): a origem das ferramentas que ele chamar. */
        private volatile String source = "text";
        /** O agente do turno (SPEC-022); {@code null} sem registro de agentes. */
        private volatile zordon.core.agents.TurnScope scope;
        private volatile String notice;
        private int toolCalls;
        private int steps;

        void attach(AiStream attached) {
            stream = attached;
            if (cancelled) {
                attached.cancel();
            }
        }

        void cancel() {
            cancelled = true;
            AiStream current = stream;
            if (current != null) {
                current.cancel();
            }
        }

        boolean isCancelled() {
            return cancelled;
        }
    }

    public TurnManager(
            ZordonEventBus bus,
            ConversationStore conversations,
            IntentRouter router,
            PromptComposer prompts,
            ProviderRegistry providers) {
        this.bus = bus;
        this.conversations = conversations;
        this.router = router;
        this.prompts = prompts;
        this.providers = providers;
    }

    /** Aceita a entrada e devolve imediatamente: a resposta chega por eventos. */
    public TurnId send(SessionId session, String text, String source) {
        return send(session, text, source, null);
    }

    /** @param agent o agente escolhido na tela; forçar sempre vence o roteador (Agentes §6) */
    public TurnId send(SessionId session, String text, String source, String agent) {
        TurnId turn = new TurnId("t_" + Long.toHexString(System.nanoTime()));
        conversations.append(session, new StoredMessage("user", text, turn, Instant.now()));
        bus.publish(EventType.USER_COMMAND, Map.of(
                "turnId", turn.value(), "sessionId", session.value(), "text", text, "source", source));

        Intent routed = router.route(text);
        if (agent != null && !agent.isBlank() && routed instanceof Intent.Model) {
            routed = new Intent.Model(agent);
        }
        switch (routed) {
            case Intent.Immediate immediate -> answerLocally(session, turn, immediate);
            case Intent.CancelCurrent cancel -> cancelEverything(session, turn);
            case Intent.Tool tool -> Thread.ofVirtual().name("zordon-turn-" + turn.value())
                    .start(() -> useTool(session, turn, tool, source));
            case Intent.Model model -> {
                // Registrado aqui, na thread de quem pediu, antes de qualquer outra
                // começar: a partir deste ponto o turno já é cancelável.
                RunningTurn handle = new RunningTurn();
                handle.source = source;
                zordon.core.agents.AgentRegistry registry = agents;
                if (registry != null) {
                    zordon.core.agents.AgentProfile profile = registry.find(model.agentId()).orElse(null);
                    if (profile == null) {
                        handle.notice = "não conheço o agente " + model.agentId() + "; quem responde é o Zordon";
                        profile = registry.general();
                    }
                    handle.scope = zordon.core.agents.TurnScope.of(profile, "voice".equals(source)
                            ? zordon.api.security.RequestOrigin.VOICE : zordon.api.security.RequestOrigin.UI, nanos);
                }
                running.put(turn, handle);
                Thread.ofVirtual()
                        .name("zordon-turn-" + turn.value())
                        .start(() -> askTheModel(session, turn, model, handle));
            }
        }
        return turn;
    }

    /** Cancela um turno específico. Cancelamento não é erro. */
    public boolean cancel(TurnId turn) {
        RunningTurn handle = running.remove(turn);
        if (handle == null) {
            return false;
        }
        handle.cancel();
        return true;
    }

    public boolean isRunning(TurnId turn) {
        return running.containsKey(turn);
    }

    /** Lista de turnos em andamento, para diagnóstico. */
    public List<String> activeTurns() {
        return running.keySet().stream().map(TurnId::value).toList();
    }

    /**
     * Rota rápida: responde sem chamar modelo nenhum. Mesmo assim publica os
     * eventos do turno — um caminho que não aparece no log é um caminho invisível.
     */
    private void answerLocally(SessionId session, TurnId turn, Intent.Immediate immediate) {
        conversations.append(session, new StoredMessage("assistant", immediate.answer(), turn, Instant.now()));
        bus.publish(EventType.AI_RESPONSE, Map.of(
                "turnId", turn.value(),
                "text", immediate.answer(),
                "done", true,
                "route", "fast:" + immediate.rule(),
                "usage", usageOf(TokenUsage.NONE, false),
                "costUsd", "0"));
    }

    /** Quem executa as ferramentas; sem ele, a rota avisa em vez de sumir. */
    public interface ToolInvoker {
        java.util.concurrent.CompletableFuture<String> invoke(String tool, Map<String, Object> args, String source,
                String turnId);
    }

    private volatile ToolCaller caller;

    /** Liga as ferramentas ao modelo (SPEC-019). Sem isto, o modelo só responde texto. */
    public void onToolCalls(ToolCaller toolCaller) {
        this.caller = java.util.Objects.requireNonNull(toolCaller, "toolCaller");
    }

    /** O que a memória sabe sobre o pedido, como bloco de dados (SPEC-021 CA-2). Vazio se nada. */
    public interface Recall {
        String about(String userText);
    }

    /** Um turno respondido pelo modelo, para a destilação (SPEC-021 CA-5). */
    public record Completed(SessionId session, TurnId turn, String userText, String answer, boolean tainted) {}

    private volatile Recall recall = text -> "";
    private volatile java.util.function.Consumer<Completed> completed = done -> { };

    private volatile zordon.core.agents.AgentRegistry agents;
    private volatile java.util.function.LongSupplier nanos = System::nanoTime;
    private volatile java.util.function.BiConsumer<zordon.core.agents.AgentProfile, String> suspended =
            (agent, reason) -> { };

    /** Agentes como configuração (SPEC-022). Sem isto, o turno é do agente geral sem teto próprio. */
    public void onAgents(zordon.core.agents.AgentRegistry registry) {
        this.agents = java.util.Objects.requireNonNull(registry, "registry");
    }

    /** O disjuntor de um turno abriu: o usuário precisa saber (SPEC-022 CA-5). */
    public void onSuspended(java.util.function.BiConsumer<zordon.core.agents.AgentProfile, String> listener) {
        this.suspended = java.util.Objects.requireNonNull(listener, "listener");
    }

    /** O relógio do orçamento de tempo dos agentes. Trocado só nos testes de tempo de parede. */
    public void nanoClock(java.util.function.LongSupplier source) {
        this.nanos = java.util.Objects.requireNonNull(source, "source");
    }

    public void onRecall(Recall memory) {
        this.recall = java.util.Objects.requireNonNull(memory, "memory");
    }

    public void onCompleted(java.util.function.Consumer<Completed> listener) {
        this.completed = java.util.Objects.requireNonNull(listener, "listener");
    }

    private volatile ToolInvoker tools = (tool, args, source, turnId) ->
            java.util.concurrent.CompletableFuture.completedFuture("As ferramentas ainda não estão disponíveis.");

    public void onTool(ToolInvoker invoker) {
        this.tools = java.util.Objects.requireNonNull(invoker, "invoker");
    }

    /** Rota rápida de ferramenta: a resposta é o resultado dela, dito como qualquer resposta. */
    private void useTool(SessionId session, TurnId turn, Intent.Tool tool, String source) {
        String answer;
        try {
            answer = tools.invoke(tool.tool(), tool.args(), source, turn.value())
                    .get(PermissionTimeout.SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            answer = "Não consegui: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()) + ".";
        }
        answerLocally(session, turn, new Intent.Immediate(answer, tool.rule()));
    }

    /** Uma ferramenta pode esperar os 60 s da autorização e mais o tempo dela. */
    private static final class PermissionTimeout {
        static final long SECONDS = 15 * 60;
    }

    private void cancelEverything(SessionId session, TurnId turn) {
        int cancelled = running.size();
        running.values().forEach(RunningTurn::cancel);
        running.clear();
        String answer = cancelled == 0 ? "Não há nada em andamento." : "Cancelado.";
        answerLocally(session, turn, new Intent.Immediate(answer, "cancelar"));
    }

    private void askTheModel(SessionId session, TurnId turn, Intent.Model intent, RunningTurn handle) {
        Resolution resolution = providers.select(ModelRole.CONVERSATION);
        if (handle.scope != null && handle.scope.agent().role() != ModelRole.CONVERSATION
                && providers.select(handle.scope.agent().role()) instanceof Resolution.Selected own) {
            // O papel do agente, quando configurado; sem ele, o da conversa.
            resolution = own;
        }
        if (handle.scope != null && handle.scope.meter().step() != null) {
            running.remove(turn);
            publishError(turn, AiException.Kind.INVALID_REQUEST, "orçamento do agente esgotado antes de começar", false);
            return;
        }
        switch (resolution) {
            case Resolution.Selected selected -> attempt(session, turn, intent, handle, selected.selection(), null);
            case Resolution.Unresolved unresolved -> {
                // Sem provider principal — sem chave, por exemplo. É exatamente o
                // caso em que uma reserva configurada precisa entrar.
                String from = unresolved.providerId() == null ? "conversation" : unresolved.providerId();
                Optional<Selection> reserve = reserveFor(null);
                if (reserve.isPresent()) {
                    attempt(session, turn, intent, handle, reserve.get(), new Fallback(from, unresolved.reason()));
                } else {
                    running.remove(turn);
                    publishError(turn, AiException.Kind.NO_CREDENTIALS, noProvider(unresolved.reason()), false);
                }
            }
        }
    }

    private void attempt(
            SessionId session, TurnId turn, Intent.Model intent, RunningTurn handle, Selection selection, Fallback fallback) {
        if (handle.isCancelled()) {
            // Cancelado antes de chegar ao modelo: nenhuma requisição, nenhum custo.
            running.remove(turn);
            publishCancelled(turn);
            return;
        }
        Map<String, Object> thinking = new HashMap<>(Map.of(
                "turnId", turn.value(),
                "model", selection.choice().model(),
                "provider", selection.providerId(),
                "agentId", handle.scope == null ? intent.agentId() : handle.scope.agent().id()));
        if (handle.notice != null) {
            thinking.put("notice", handle.notice);
        }
        if (fallback != null) {
            thinking.put("fallbackFrom", fallback.fromProvider());
            thinking.put("reason", fallback.reason());
            log.warn("turno {}: reserva {} assumiu no lugar de {} — {}",
                    turn.value(), selection.providerId(), fallback.fromProvider(), fallback.reason());
        }
        bus.publish(EventType.AI_THINKING, thinking);

        List<zordon.ai.AiMessage> messages = prompts.toMessages(
                conversations.conversation(session, PromptComposer.MAX_HISTORY_MESSAGES));
        String userText = messages.isEmpty() ? "" : messages.getLast().text();
        messages = withMemory(messages, userText);
        ToolCaller toolCaller = caller;
        List<zordon.ai.ToolSpec> offered = toolCaller == null || messages.isEmpty() ? List.of()
                : handle.scope == null ? toolCaller.offer(userText) : toolCaller.offer(userText, handle.scope);
        step(new Exchange(session, turn, intent, handle, selection, fallback), messages, offered);
    }

    /**
     * A memória entra no começo da última mensagem do usuário, e só na requisição:
     * o prompt de sistema fica estável para o cache, e a conversa gravada fica limpa.
     */
    private List<zordon.ai.AiMessage> withMemory(List<zordon.ai.AiMessage> messages, String userText) {
        if (messages.isEmpty() || messages.getLast().role() != zordon.ai.Role.USER) {
            return messages;
        }
        String block;
        try {
            block = recall.about(userText);
        } catch (RuntimeException e) {
            log.warn("memória indisponível neste turno: {}", e.getMessage());
            return messages;
        }
        if (block == null || block.isBlank()) {
            return messages;
        }
        List<zordon.ai.AiMessage> out = new java.util.ArrayList<>(messages.subList(0, messages.size() - 1));
        out.add(new zordon.ai.AiMessage(zordon.ai.Role.USER,
                List.of(new zordon.ai.ContentBlock.Text(block + "\n\n" + userText))));
        return out;
    }

    /** Uma volta ao modelo. Se ele pedir ferramentas, elas rodam e vem outra volta (SPEC-019). */
    private void step(Exchange ex, List<zordon.ai.AiMessage> messages, List<zordon.ai.ToolSpec> offered) {
        AiRequest request = AiRequest.builder(ex.selection().choice().model())
                .systemPrompt(ex.handle().scope == null ? prompts.systemPrompt()
                        : prompts.systemPrompt() + "\n\n" + ex.handle().scope.agent().prompt())
                .tools(offered)
                .messages(messages)
                .effort(ex.selection().choice().effortIfAny().orElse(null))
                .maxOutputTokens(MAX_OUTPUT_TOKENS)
                .timeout(Duration.ofMinutes(5))
                .build();

        TurnListener listener = new TurnListener(bus, ex.turn());
        AiStream stream = ex.selection().provider().stream(request, listener);
        ex.handle().attach(stream);
        stream.result().whenComplete((response, failure) -> {
            if (failure == null) {
                answered(ex, messages, offered, response);
            } else {
                failed(ex, listener, failure);
            }
        });
    }

    /** O modelo respondeu: ou o teto parou, ou vêm ferramentas, ou o turno acaba. */
    private void answered(Exchange ex, List<zordon.ai.AiMessage> messages, List<zordon.ai.ToolSpec> offered,
            AiResponse response) {
        ToolCaller toolCaller = caller;
        zordon.core.agents.TurnScope scope = ex.handle().scope;
        if (scope != null) {
            scope.meter().tokens(response.usage().inputTokens() + response.usage().outputTokens());
        }
        String exceeded = scope == null ? null : scope.meter().exceeded();
        if (exceeded != null && response.stopReason() == StopReason.TOOL_USE) {
            stopAtLimit(ex, response, zordon.core.agents.AgentRunner.limitNotice(scope.agent().id(), exceeded));
            return;
        }
        if (response.stopReason() == StopReason.TOOL_USE && !response.toolCalls().isEmpty()
                && toolCaller != null && !ex.handle().isCancelled()) {
            useTools(ex, messages, offered, response, toolCaller);
            return;
        }
        running.remove(ex.turn());
        boolean tainted = tainted(ex.turn());
        endTools(ex.turn());
        completeTurn(ex.session(), ex.turn(), response, ex.selection(), ex.fallback(), tainted);
    }

    /**
     * O turno falhou.
     *
     * <p>Único lugar em que a falha vira evento: publicá-la também no listener
     * dava dois erros na tela para uma causa só.
     */
    private void failed(Exchange ex, TurnListener listener, Throwable failure) {
        AiException cause = asAiException(failure);
        Optional<Selection> reserve = ex.fallback() == null
                        && !listener.producedText()
                        && !ex.handle().isCancelled()
                        && FALLBACK_ELIGIBLE.contains(cause.kind())
                ? reserveFor(ex.selection())
                : Optional.empty();
        if (reserve.isPresent()) {
            attempt(ex.session(), ex.turn(), ex.intent(), ex.handle(), reserve.get(),
                    new Fallback(ex.selection().providerId(), cause.getMessage()));
            return;
        }
        running.remove(ex.turn());
        String message = ex.fallback() == null
                ? cause.getMessage()
                : cause.getMessage() + " (a reserva entrou porque: " + ex.fallback().reason() + ")";
        publishError(ex.turn(), cause.kind(), message, cause.isRetryable());
    }

    /**
     * Roda os pedidos de ferramenta do modelo, um de cada vez, e volta ao modelo
     * com os resultados. Tetos: 25 chamadas e 15 voltas por turno (SPEC-019 CA-3).
     */
    private void useTools(Exchange ex, List<zordon.ai.AiMessage> messages,
            List<zordon.ai.ToolSpec> offered, AiResponse response, ToolCaller toolCaller) {
        List<zordon.ai.ContentBlock.ToolUse> calls = response.toolCalls();
        zordon.core.agents.TurnScope scope = ex.handle().scope;
        if (overToolLimit(ex, response, scope, calls.size())) {
            return;
        }
        ex.handle().toolCalls += calls.size();
        ex.handle().steps++;
        Thread.ofVirtual().name("zordon-tools-" + ex.turn().value()).start(() -> {
            List<zordon.ai.ContentBlock> results = runTools(ex, calls, scope, toolCaller);
            if (scope != null && scope.guard().tripped() != null && !ex.handle().isCancelled()) {
                suspend(ex, response, scope);
                return;
            }
            if (ex.handle().isCancelled()) {
                running.remove(ex.turn());
                endTools(ex.turn());
                publishCancelled(ex.turn());
                return;
            }
            List<zordon.ai.AiMessage> next = new java.util.ArrayList<>(messages);
            next.add(new zordon.ai.AiMessage(zordon.ai.Role.ASSISTANT, response.content()));
            next.add(new zordon.ai.AiMessage(zordon.ai.Role.USER, results));
            step(ex, next, offered);
        });
    }

    /** @return {@code true} quando um teto parou o turno e nada mais deve rodar */
    private boolean overToolLimit(Exchange ex, AiResponse response, zordon.core.agents.TurnScope scope, int calls) {
        if (scope == null) {
            if (ex.handle().toolCalls + calls > MAX_TOOL_CALLS || ex.handle().steps + 1 > MAX_STEPS) {
                stopAtLimit(ex, response, TOOL_LIMIT_NOTICE);
                return true;
            }
            return false;
        }
        String over = scope.meter().calls(calls);
        if (over == null) {
            over = scope.meter().step();   // a volta que vem depois das ferramentas
        }
        if (over == null) {
            return false;
        }
        stopAtLimit(ex, response, zordon.core.agents.AgentRunner.limitNotice(scope.agent().id(), over));
        return true;
    }

    /** Uma ferramenta de cada vez. Falha de uma vira resultado de erro, não fim do turno. */
    private List<zordon.ai.ContentBlock> runTools(Exchange ex, List<zordon.ai.ContentBlock.ToolUse> calls,
            zordon.core.agents.TurnScope scope, ToolCaller toolCaller) {
        List<zordon.ai.ContentBlock> results = new java.util.ArrayList<>();
        for (zordon.ai.ContentBlock.ToolUse call : calls) {
            if (ex.handle().isCancelled()) {
                break;
            }
            zordon.ai.ContentBlock.ToolResult result;
            try {
                result = (scope == null ? toolCaller.call(call, ex.handle().source, ex.turn().value())
                        : toolCaller.call(call, ex.handle().source, ex.turn().value(), scope))
                        .get(PermissionTimeout.SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                result = new zordon.ai.ContentBlock.ToolResult(call.callId(),
                        "a ferramenta falhou: " + e.getMessage(), true);
            }
            results.add(result);
            if (scope != null && scope.guard().tripped() != null) {
                break;
            }
        }
        return results;
    }

    /** O disjuntor do agente abriu no meio das ferramentas: para, avisa e explica. */
    private void suspend(Exchange ex, AiResponse response, zordon.core.agents.TurnScope scope) {
        String reason = scope.guard().tripped();
        log.warn("turno {}: execução do agente {} suspensa — {}", ex.turn().value(), scope.agent().id(), reason);
        suspended.accept(scope.agent(), reason);
        stopAtLimit(ex, response, "Parei: a execução do agente " + scope.agent().id()
                + " foi suspensa (" + reason + "). Nada mais será feito até você decidir.");
    }

    /** Termina o turno com o que já havia e o motivo de parar: nunca em silêncio. */
    private void stopAtLimit(Exchange ex, AiResponse response, String notice) {
        running.remove(ex.turn());
        boolean tainted = tainted(ex.turn());
        endTools(ex.turn());
        String partial = response.text().isBlank() ? notice : response.text() + " " + notice;
        completeTurn(ex.session(), ex.turn(), new AiResponse(List.of(new zordon.ai.ContentBlock.Text(partial)),
                StopReason.END_TURN, response.usage(), response.cost(), response.model(), response.latency(), null,
                response.isUsageEstimated()), ex.selection(), ex.fallback(), tainted);
    }

    private boolean tainted(TurnId turn) {
        ToolCaller toolCaller = caller;
        return toolCaller != null && toolCaller.tainted(turn.value());
    }

    private void endTools(TurnId turn) {
        ToolCaller toolCaller = caller;
        if (toolCaller != null) {
            toolCaller.endTurn(turn.value());
        }
    }

    /**
     * Quando ninguém pode responder, o motivo de cada provider — inclusive o da
     * reserva. Dizer só o primeiro deixaria o usuário arrumando o que não bastava.
     */
    private String noProvider(String reason) {
        String detail = providers.describe().entrySet().stream()
                .filter(entry -> entry.getValue().startsWith("indisponível"))
                .map(entry -> entry.getKey() + ": " + entry.getValue().replace("indisponível — ", ""))
                .collect(java.util.stream.Collectors.joining("; "));
        return detail.isBlank() ? reason
                : "Nenhum provider disponível — " + detail
                        + ". A assinatura é a primeira opção; a chave de API, a última.";
    }

    /** A reserva só serve se for outra coisa: o mesmo provider e modelo falhariam igual. */
    private Optional<Selection> reserveFor(Selection failed) {
        // Sem papel `fallback` declarado, vale a ordem de preferência: o próximo da
        // fila, com a chave paga por uso em último (SPEC-018 §3).
        Optional<Selection> candidate = providers.select(ModelRole.FALLBACK) instanceof Resolution.Selected selected
                ? Optional.of(selected.selection())
                : providers.preferred(failed == null ? null : failed.providerId());
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        Selection reserve = candidate.get();
        boolean same = failed != null
                && reserve.providerId().equals(failed.providerId())
                && reserve.choice().model().equals(failed.choice().model());
        return same ? Optional.empty() : Optional.of(reserve);
    }

    private void completeTurn(SessionId session, TurnId turn, AiResponse response, Selection selection,
            Fallback fallback, boolean tainted) {
        if (response.stopReason() == StopReason.REFUSAL) {
            // Recusa chega como sucesso HTTP. Tratá-la como resposta vazia produz
            // "o Zordon não respondeu" sem causa aparente.
            publishError(turn, AiException.Kind.INVALID_REQUEST,
                    response.refusal().orElse("O modelo recusou responder."), false);
            return;
        }
        String text = response.text();
        if (!text.isBlank()) {
            conversations.append(session, new StoredMessage("assistant", text, turn, Instant.now()));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("turnId", turn.value());
        payload.put("text", text);
        payload.put("done", true);
        payload.put("provider", selection.providerId());
        payload.put("model", response.model());
        payload.put("stopReason", response.stopReason().name());
        payload.put("usage", usageOf(response.usage(), response.isUsageEstimated()));
        payload.put("costUsd", response.cost().amount().toPlainString());
        payload.put("latencyMs", response.latency().toMillis());
        if (fallback != null) {
            payload.put("fallbackFrom", fallback.fromProvider());
            payload.put("fallbackReason", fallback.reason());
        }
        bus.publish(EventType.AI_RESPONSE, payload);

        if (!text.isBlank()) {
            String asked = conversations.conversation(session, PromptComposer.MAX_HISTORY_MESSAGES).stream()
                    .filter(message -> turn.equals(message.turn()) && "user".equals(message.role()))
                    .map(StoredMessage::text).findFirst().orElse("");
            try {
                completed.accept(new Completed(session, turn, asked, text, tainted));
            } catch (RuntimeException e) {
                log.warn("turno {}: aviso de conclusão falhou: {}", turn.value(), e.getMessage());
            }
        }

        log.info("turno {} concluído por {} em {} ms · {} tokens{} · {}",
                turn.value(), selection.providerId(), response.latency().toMillis(),
                response.usage().totalTokens(), response.isUsageEstimated() ? " (estimado)" : "", response.cost());
    }

    /**
     * O futuro pode entregar a exceção do provider direto ou embrulhada numa
     * {@code CompletionException}, dependendo de como foi composto. Olhar só a
     * causa classificava errado o caso direto.
     */
    private static AiException asAiException(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AiException typed) {
                return typed;
            }
            current = current.getCause();
        }
        return new AiException(AiException.Kind.UNAVAILABLE, "Falha inesperada no provider: " + failure.getMessage());
    }

    private void publishCancelled(TurnId turn) {
        bus.publish(EventType.AI_RESPONSE, Map.of(
                "turnId", turn.value(), "text", "", "done", true, "stopReason", StopReason.CANCELLED.name()));
    }

    private void publishError(TurnId turn, AiException.Kind kind, String message, boolean retryable) {
        bus.publish(EventType.AI_ERROR, Map.of(
                "turnId", turn.value(), "kind", kind.name(), "message", message, "retryable", retryable));
    }

    /**
     * O uso de cache é publicado desde o primeiro turno de propósito: leitura de
     * cache sempre zero é o sintoma do maior risco do M1, e um número que ninguém
     * vê não é um número. Estimativa vai marcada: exibida como medida, seria mentira.
     */
    private static Map<String, Object> usageOf(TokenUsage usage, boolean estimated) {
        return Map.of(
                "inputTokens", usage.inputTokens(),
                "outputTokens", usage.outputTokens(),
                "cacheCreationTokens", usage.cacheCreationTokens(),
                "cacheReadTokens", usage.cacheReadTokens(),
                "estimated", estimated);
    }

    /** Traduz o fluxo do provider em eventos do barramento. */
}
