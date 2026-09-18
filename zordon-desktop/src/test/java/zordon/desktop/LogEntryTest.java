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
package zordon.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.trace.AcceptanceCriteria;

/**
 * A projeção de evento em linha de log é regra, não apresentação — por isso vive
 * fora do controller e tem teste próprio
 * (docs/process/code-standards.md §9).
 */
class LogEntryTest {

    @AcceptanceCriteria("SPEC-003/CA-13")
    @Test
    void oComandoDoUsuarioApareceComOTextoEAOrigem() {
        LogEntry entry = LogEntry.of(event(EventType.USER_COMMAND, Map.of("text", "oi", "source", "voice")));

        assertThat(entry.detail()).isEqualTo("\"oi\" · voice");
        assertThat(entry.topic()).isEqualTo("chat");
    }

    @AcceptanceCriteria("SPEC-003/CA-13")
    @Test
    void aRespostaCompletaMostraTokensCustoELatencia() {
        LogEntry entry = LogEntry.of(event(EventType.AI_RESPONSE, Map.of(
                "done", true,
                "usage", Map.of("inputTokens", 1_240, "outputTokens", 320),
                "costUsd", "0.0042",
                "latencyMs", 1_870)));

        assertThat(entry.detail())
                .isEqualTo("resposta completa · 1240 entrada / 320 saída tok · US$ 0.0042 · 1870 ms");
    }

    @AcceptanceCriteria("SPEC-003/CA-13")
    @Test
    void fragmentoDeStreamingNaoDespejaOPayloadNaTela() {
        // Uma linha por fragmento tornaria o log ilegível justamente quando ele é
        // mais necessário.
        LogEntry entry = LogEntry.of(event(EventType.AI_RESPONSE, Map.of("delta", "São ", "done", false)));

        assertThat(entry.detail()).isEqualTo("fragmento");
    }

    @AcceptanceCriteria("SPEC-003/CA-13")
    @Test
    void aRotaRapidaSeIdentificaComoTal() {
        LogEntry entry = LogEntry.of(event(EventType.AI_RESPONSE, Map.of(
                "done", true, "route", "fast:hora", "text", "São 22h31.")));

        assertThat(entry.detail()).isEqualTo("resposta por rota rápida (fast:hora)");
    }

    @AcceptanceCriteria("SPEC-004/CA-9")
    @Test
    void aEntradaDaReservaFicaNoLogComOMotivo() {
        LogEntry entry = LogEntry.of(event(EventType.AI_THINKING, Map.of(
                "model", "qwen2.5:7b", "provider", "ollama", "agentId", "zordon",
                "fallbackFrom", "anthropic", "reason", "A conta da API está sem crédito.")));

        assertThat(entry.detail()).isEqualTo(
                "reserva ollama · qwen2.5:7b · agente zordon · no lugar de anthropic: A conta da API está sem crédito.");
    }

    @AcceptanceCriteria("SPEC-003/CA-13")
    @Test
    void oErroMostraCategoriaEMotivo() {
        LogEntry entry = LogEntry.of(event(EventType.AI_ERROR, Map.of(
                "kind", "RATE_LIMITED", "message", "limite de taxa do provider")));

        assertThat(entry.detail()).isEqualTo("RATE_LIMITED · limite de taxa do provider");
    }

    private EventEnvelope event(EventType type, Map<String, Object> payload) {
        return new EventEnvelope(42, Instant.parse("2026-09-17T22:31:04Z"), type, payload);
    }
}
