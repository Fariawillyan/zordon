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
import java.util.stream.Collectors;
import zordon.ai.ModelRole;
import zordon.ai.registry.ProviderRegistry;
import zordon.ai.registry.ProviderRegistry.Resolution;
import zordon.ai.registry.ProviderRegistry.Selection;

/** Quem responde: o papel do agente ou o da conversa, e a reserva quando o principal falha (ADR-0026). */
final class TurnProviders {

    private final ProviderRegistry providers;

    TurnProviders(ProviderRegistry providers) {
        this.providers = providers;
    }

    Resolution choose(RunningTurn handle) {
        Resolution resolution = providers.select(ModelRole.CONVERSATION);
        if (handle.scope != null && handle.scope.agent().role() != ModelRole.CONVERSATION
                && providers.select(handle.scope.agent().role()) instanceof Resolution.Selected own) {
            // O papel do agente, quando configurado; sem ele, o da conversa.
            resolution = own;
        }
        return resolution;
    }

    /**
     * Quando ninguém pode responder, o motivo de cada provider — inclusive o da
     * reserva. Dizer só o primeiro deixaria o usuário arrumando o que não bastava.
     */
    String noProvider(String reason) {
        String detail = providers.describe().entrySet().stream()
                .filter(entry -> entry.getValue().startsWith("indisponível"))
                .map(entry -> entry.getKey() + ": " + entry.getValue().replace("indisponível — ", ""))
                .collect(Collectors.joining("; "));
        return detail.isBlank() ? reason
                : "Nenhum provider disponível — " + detail
                        + ". A assinatura é a primeira opção; a chave de API, a última.";
    }

    /** A reserva só serve se for outra coisa: o mesmo provider e modelo falhariam igual. */
    Optional<Selection> reserveFor(Selection failed) {
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
}
