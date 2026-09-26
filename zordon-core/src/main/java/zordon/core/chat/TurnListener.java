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

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.ai.AiException;
import zordon.ai.AiStreamListener;
import zordon.api.TurnId;
import zordon.api.event.EventType;
import zordon.core.event.ZordonEventBus;

/** O que chega enquanto o modelo escreve, virado em evento do turno (SPEC-003). */
final class TurnListener implements AiStreamListener {

    private static final Logger log = LoggerFactory.getLogger(TurnListener.class);

    private final ZordonEventBus bus;
    private final TurnId turn;
    private volatile boolean producedText;

    TurnListener(ZordonEventBus bus, TurnId turn) {
        this.bus = bus;
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
