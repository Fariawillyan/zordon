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

import java.util.Optional;
import java.util.Set;
import zordon.ai.AiException;
import zordon.ai.registry.ProviderRegistry;
import zordon.ai.registry.ProviderRegistry.Resolution;
import zordon.ai.registry.ProviderRegistry.Selection;
import zordon.api.SessionId;
import zordon.api.TurnId;

/**
 * Do pedido ao modelo: pede ao {@link ProviderRegistry} o provider do papel
 * {@code conversation} e, se ele falhar antes de responder, o do papel
 * {@code fallback} (ADR-0026). Quando ninguém pode responder, diz por quê.
 */
final class TurnRouting {

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

    private final TurnParts parts;
    private final TurnProviders providers;
    private final ModelRound round;

    TurnRouting(TurnParts parts, ProviderRegistry providers) {
        this.parts = parts;
        this.providers = new TurnProviders(providers);
        this.round = new ModelRound(parts, this::failed);
    }

    void ask(SessionId session, TurnId turn, Intent.Model intent, RunningTurn handle) {
        if (handle.scope != null && handle.scope.meter().step() != null) {
            parts.running().remove(turn);
            parts.events().error(turn, AiException.Kind.INVALID_REQUEST, "orçamento do agente esgotado antes de começar",
                    false);
            return;
        }
        switch (providers.choose(handle)) {
            case Resolution.Selected selected -> attempt(new TurnExchange(session, turn, intent, handle,
                    selected.selection(), null));
            case Resolution.Unresolved unresolved -> {
                // Sem provider principal — sem chave, por exemplo. É exatamente o
                // caso em que uma reserva configurada precisa entrar.
                String from = unresolved.providerId() == null ? "conversation" : unresolved.providerId();
                Optional<Selection> reserve = providers.reserveFor(null);
                if (reserve.isPresent()) {
                    attempt(new TurnExchange(session, turn, intent, handle, reserve.get(),
                            new TurnFallback(from, unresolved.reason())));
                } else {
                    parts.running().remove(turn);
                    parts.events().error(turn, AiException.Kind.NO_CREDENTIALS, providers.noProvider(unresolved.reason()),
                            false);
                }
            }
        }
    }

    private void attempt(TurnExchange ex) {
        if (ex.handle().isCancelled()) {
            // Cancelado antes de chegar ao modelo: nenhuma requisição, nenhum custo.
            parts.running().remove(ex.turn());
            parts.events().cancelled(ex.turn());
            return;
        }
        parts.events().thinking(ex);
        TurnPrompt.Prepared prepared = parts.prompt().prepare(ex.session(), ex.handle());
        round.step(ex, prepared.messages(), prepared.offered());
    }

    /**
     * O turno falhou.
     *
     * <p>Único lugar em que a falha vira evento: publicá-la também no listener
     * dava dois erros na tela para uma causa só.
     */
    private void failed(TurnExchange ex, boolean producedText, Throwable failure) {
        AiException cause = asAiException(failure);
        Optional<Selection> reserve = ex.fallback() == null
                        && !producedText
                        && !ex.handle().isCancelled()
                        && FALLBACK_ELIGIBLE.contains(cause.kind())
                ? providers.reserveFor(ex.selection())
                : Optional.empty();
        if (reserve.isPresent()) {
            attempt(new TurnExchange(ex.session(), ex.turn(), ex.intent(), ex.handle(), reserve.get(),
                    new TurnFallback(ex.selection().providerId(), cause.getMessage())));
            return;
        }
        parts.running().remove(ex.turn());
        String message = ex.fallback() == null
                ? cause.getMessage()
                : cause.getMessage() + " (a reserva entrou porque: " + ex.fallback().reason() + ")";
        parts.events().error(ex.turn(), cause.kind(), message, cause.isRetryable());
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
}
