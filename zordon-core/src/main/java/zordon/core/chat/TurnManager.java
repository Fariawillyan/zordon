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
import zordon.ai.AiStreamListener;
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
        TurnId turn = new TurnId("t_" + Long.toHexString(System.nanoTime()));
        conversations.append(session, new StoredMessage("user", text, turn, Instant.now()));
        bus.publish(EventType.USER_COMMAND, Map.of(
                "turnId", turn.value(), "sessionId", session.value(), "text", text, "source", source));

        switch (router.route(text)) {
            case Intent.Immediate immediate -> answerLocally(session, turn, immediate);
            case Intent.CancelCurrent cancel -> cancelEverything(session, turn);
            case Intent.Model model -> {
                // Registrado aqui, na thread de quem pediu, antes de qualquer outra
                // começar: a partir deste ponto o turno já é cancelável.
                RunningTurn handle = new RunningTurn();
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

    private void cancelEverything(SessionId session, TurnId turn) {
        int cancelled = running.size();
        running.values().forEach(RunningTurn::cancel);
        running.clear();
        String answer = cancelled == 0 ? "Não há nada em andamento." : "Cancelado.";
        answerLocally(session, turn, new Intent.Immediate(answer, "cancelar"));
    }

    private void askTheModel(SessionId session, TurnId turn, Intent.Model intent, RunningTurn handle) {
        switch (providers.select(ModelRole.CONVERSATION)) {
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
                    publishError(turn, AiException.Kind.NO_CREDENTIALS, unresolved.reason(), false);
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
                "agentId", intent.agentId()));
        if (fallback != null) {
            thinking.put("fallbackFrom", fallback.fromProvider());
            thinking.put("reason", fallback.reason());
            log.warn("turno {}: reserva {} assumiu no lugar de {} — {}",
                    turn.value(), selection.providerId(), fallback.fromProvider(), fallback.reason());
        }
        bus.publish(EventType.AI_THINKING, thinking);

        AiRequest request = AiRequest.builder(selection.choice().model())
                .systemPrompt(prompts.systemPrompt())
                .messages(prompts.toMessages(
                        conversations.conversation(session, PromptComposer.MAX_HISTORY_MESSAGES)))
                .effort(selection.choice().effortIfAny().orElse(null))
                .maxOutputTokens(MAX_OUTPUT_TOKENS)
                .timeout(Duration.ofMinutes(5))
                .build();

        TurnListener listener = new TurnListener(turn);
        AiStream stream = selection.provider().stream(request, listener);
        handle.attach(stream);
        stream.result().whenComplete((response, failure) -> {
            if (failure == null) {
                running.remove(turn);
                completeTurn(session, turn, response, selection, fallback);
                return;
            }
            // Único lugar em que a falha do turno vira evento: publicá-la também
            // no listener dava dois erros na tela para uma causa só.
            AiException cause = asAiException(failure);
            Optional<Selection> reserve = fallback == null
                            && !listener.producedText()
                            && !handle.isCancelled()
                            && FALLBACK_ELIGIBLE.contains(cause.kind())
                    ? reserveFor(selection)
                    : Optional.empty();
            if (reserve.isPresent()) {
                attempt(session, turn, intent, handle, reserve.get(),
                        new Fallback(selection.providerId(), cause.getMessage()));
                return;
            }
            running.remove(turn);
            String message = fallback == null
                    ? cause.getMessage()
                    : cause.getMessage() + " (a reserva entrou porque: " + fallback.reason() + ")";
            publishError(turn, cause.kind(), message, cause.isRetryable());
        });
    }

    /** A reserva só serve se for outra coisa: o mesmo provider e modelo falhariam igual. */
    private Optional<Selection> reserveFor(Selection failed) {
        if (!(providers.select(ModelRole.FALLBACK) instanceof Resolution.Selected selected)) {
            return Optional.empty();
        }
        Selection reserve = selected.selection();
        boolean same = failed != null
                && reserve.providerId().equals(failed.providerId())
                && reserve.choice().model().equals(failed.choice().model());
        return same ? Optional.empty() : Optional.of(reserve);
    }

    private void completeTurn(
            SessionId session, TurnId turn, AiResponse response, Selection selection, Fallback fallback) {
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
    private final class TurnListener implements AiStreamListener {

        private final TurnId turn;
        private volatile boolean producedText;

        private TurnListener(TurnId turn) {
            this.turn = turn;
        }

        /** Depois do primeiro fragmento não há reserva: não dá para emendar dois modelos numa frase. */
        boolean producedText() {
            return producedText;
        }

        @Override
        public void onTextDelta(String delta) {
            producedText = true;
            bus.publish(EventType.AI_RESPONSE, Map.of("turnId", turn.value(), "delta", delta, "done", false));
        }

        @Override
        public void onThinking(String summary) {
            bus.publish(EventType.AI_THINKING, Map.of("turnId", turn.value(), "summary", summary));
        }

        /**
         * Só registra. Se a falha encerra o turno, ela chega pelo resultado do
         * stream; se não encerra (argumento de ferramenta inválido), o turno segue
         * e não há erro para mostrar ao usuário.
         */
        @Override
        public void onError(AiException e) {
            log.debug("turno {}: {} — {}", turn.value(), e.kind(), e.getMessage());
        }
    }
}
