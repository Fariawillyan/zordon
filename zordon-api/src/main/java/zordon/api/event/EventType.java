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
package zordon.api.event;

/**
 * Catálogo de eventos (docs/api/zwp-protocol.md §6).
 *
 * <p>Cada entrada carrega o seu tópico: é o que impede um evento de nascer órfão ou
 * de ser publicado em um tópico que ninguém assina para esse tipo de informação.
 * O enum cresce com os marcos — um evento entra aqui quando alguém o publica.
 */
public enum EventType {

    /** Núcleo iniciou. Carrega {@code startId} e {@code version}. */
    CORE_STARTED(Topic.SYSTEM),

    /** Algo digno de atenção fora do fluxo normal. */
    SYSTEM_ALERT(Topic.SYSTEM),

    /** Entrada do usuário aceita, por texto ou por voz. */
    USER_COMMAND(Topic.CHAT),

    /** O modelo está raciocinando; alimenta a linha "pensando…" da interface. */
    AI_THINKING(Topic.CHAT),

    /** Fragmento ou fim da resposta do modelo. */
    AI_RESPONSE(Topic.CHAT),

    /** Falha no turno, com {@code retryable} explícito para a UI decidir. */
    AI_ERROR(Topic.CHAT);

    private final String topic;

    EventType(String topic) {
        this.topic = topic;
    }

    public String topic() {
        return topic;
    }
}
