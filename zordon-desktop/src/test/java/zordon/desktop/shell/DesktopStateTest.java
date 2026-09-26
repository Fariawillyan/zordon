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
package zordon.desktop.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.trace.AcceptanceCriteria;
import zordon.zwp.CoreConnection;

class DesktopStateTest {

    private final DesktopState state = new DesktopState();

    @AcceptanceCriteria("SPEC-005/CA-3")
    @Test
    void oCabecalhoDizOEstadoDoNucleoEAVozIndisponivel() {
        assertThat(state.connection().label().get()).isEqualTo("Conectando…");

        state.online("0.1.0");
        assertThat(state.connection().label().get()).isEqualTo("Núcleo conectado");

        state.offline("conexão encerrada");
        assertThat(state.connection().label().get()).isEqualTo("Núcleo offline");
        assertThat(DesktopState.VOICE_UNAVAILABLE).isEqualTo("Voz indisponível");
    }

    @AcceptanceCriteria("SPEC-005/CA-4")
    @Test
    void offlineBloqueiaOComposerComMotivoEPreservaORascunho() {
        state.online("0.1.0");
        state.conversation().draftProperty().set("metade de uma pergunta");

        state.offline("conexão encerrada");

        assertThat(state.conversation().composerBlockedReason().get()).contains("offline").contains("rascunho fica guardado");
        assertThat(state.conversation().draftProperty().get()).isEqualTo("metade de uma pergunta");
        assertThat(state.connection().reconnectionsProperty().get()).isEqualTo(1);
        assertThat(state.connection().lastSyncProperty().get()).isNotNull();
    }

    @AcceptanceCriteria("SPEC-005/CA-4")
    @Test
    void falharAntesDaPrimeiraConexaoNaoContaComoReconexao() {
        state.offline("núcleo não publicou endpoint.json");

        assertThat(state.connection().stateProperty().get()).isEqualTo(CoreConnection.State.OFFLINE);
        assertThat(state.connection().reconnectionsProperty().get()).isZero();
    }

    @AcceptanceCriteria("SPEC-005/CA-5")
    @Test
    void semProviderOAvisoTrazOMotivoDoNucleoEOCaminho() {
        state.diagnostics(diagnostics(false, false));

        assertThat(state.providerWarning()).hasValueSatisfying(warning -> assertThat(warning)
                .contains("defina ANTHROPIC_API_KEY")
                .contains("quickstart.md, passo 4"));
    }

    @AcceptanceCriteria("SPEC-005/CA-5")
    @Test
    void comReservaProntaOAvisoDizQueAReservaResponde() {
        state.diagnostics(diagnostics(false, true));

        assertThat(state.providerWarning()).hasValueSatisfying(warning -> assertThat(warning)
                .startsWith("Respondendo pela reserva (ollama)"));
    }

    @AcceptanceCriteria("SPEC-005/CA-10")
    @Test
    void oConsumoDaSessaoSomaOsTurnosEAEstimativaContagia() {
        state.accept(response(100, 20, "0.0010", false));
        state.accept(response(50, 10, "0.0005", true));

        SessionUsage usage = state.conversation().usageProperty().get();
        assertThat(usage.turns()).isEqualTo(2);
        assertThat(usage.inputTokens()).isEqualTo(150);
        assertThat(usage.costUsd()).isEqualByComparingTo(new BigDecimal("0.0015"));
        assertThat(usage.estimated()).isTrue();
        assertThat(usage.tokensLabel()).isEqualTo("≈180 tokens");
    }

    @AcceptanceCriteria("SPEC-005/CA-10")
    @Test
    void aExecucaoAtualMostraQuemRespondeuECusto() {
        state.accept(response(1240, 320, "0.0123", false));

        TurnSummary turn = state.conversation().lastTurnProperty().get();
        assertThat(turn.footer())
                .isEqualTo("anthropic · claude-opus-5 · 1240 tok entrada · 320 tok saída · 0 de cache · US$ 0.0123");
        assertThat(turn.latencyMs()).isEqualTo(1870);
        assertThat(state.conversation().turnRunningProperty().get()).isFalse();
    }

    private static EventEnvelope response(long input, long output, String cost, boolean estimated) {
        return new EventEnvelope(1, Instant.now(), EventType.AI_RESPONSE, Map.of(
                "turnId", "t_1",
                "done", true,
                "provider", "anthropic",
                "model", "claude-opus-5",
                "usage", Map.of("inputTokens", input, "outputTokens", output, "cacheReadTokens", 0L,
                        "estimated", estimated),
                "costUsd", cost,
                "latencyMs", 1870L));
    }

    private static Map<String, Object> diagnostics(boolean conversationReady, boolean fallbackReady) {
        return Map.of(
                "core", Map.of("version", "0.1.0", "startId", "01X", "uptimeSeconds", 42L),
                "roles", Map.of(
                        "conversation", conversationReady
                                ? Map.of("provider", "anthropic", "model", "claude-opus-5", "ready", true)
                                : Map.of("provider", "anthropic", "model", "claude-opus-5", "ready", false,
                                        "reason", "provider 'anthropic' indisponível: defina ANTHROPIC_API_KEY"),
                        "fallback", Map.of("provider", "ollama", "model", "qwen2.5:7b", "ready", fallbackReady)));
    }
}
