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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import zordon.api.event.EventEnvelope;

/**
 * Traz os eventos do núcleo para a thread da interface <strong>em lote</strong>.
 *
 * <p>Sem isto, uma rajada — reconexão com replay, streaming de resposta — gera
 * centenas de {@code Platform.runLater} e congela a janela por segundos. Com lote
 * de 33 ms, vira uma atualização
 * ([UI §4](../../../../../docs/specs/ui/design.md#4-regras-de-threading)).
 */
final class UiEventPump {

    /** Teto de 30 atualizações por segundo, como a norma de threading exige. */
    private static final long INTERVAL_NANOS = 33_000_000L;

    private final ConcurrentLinkedQueue<EventEnvelope> pending = new ConcurrentLinkedQueue<>();
    private final Consumer<List<EventEnvelope>> sink;
    private final AnimationTimer timer;

    UiEventPump(Consumer<List<EventEnvelope>> sink) {
        this.sink = sink;
        this.timer = new AnimationTimer() {
            private long lastFlush;

            @Override
            public void handle(long now) {
                if (now - lastFlush < INTERVAL_NANOS) {
                    return;
                }
                lastFlush = now;
                flush();
            }
        };
    }

    /** Chamado de qualquer thread. Nunca bloqueia e nunca toca a interface. */
    void offer(EventEnvelope event) {
        pending.add(event);
    }

    void start() {
        timer.start();
    }

    void stop() {
        timer.stop();
    }

    private void flush() {
        if (pending.isEmpty()) {
            return;
        }
        List<EventEnvelope> batch = new ArrayList<>();
        EventEnvelope event;
        while ((event = pending.poll()) != null) {
            batch.add(event);
        }
        sink.accept(batch);
    }
}
