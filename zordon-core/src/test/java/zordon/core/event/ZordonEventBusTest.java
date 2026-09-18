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
package zordon.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.api.trace.AcceptanceCriteria;

class ZordonEventBusTest {

    private final ZordonEventBus bus = new ZordonEventBus("01TESTE00000000000000000000");

    @Test
    void sequenciaEhMonotonicaDentroDaInicializacao() {
        EventEnvelope first = bus.publish(EventType.CORE_STARTED, Map.of());
        EventEnvelope second = bus.publish(EventType.SYSTEM_ALERT, Map.of());

        assertThat(first.seq()).isEqualTo(1);
        assertThat(second.seq()).isEqualTo(2);
    }

    @Test
    void assinanteRecebeApenasOsTopicosQueAssinou() throws Exception {
        List<EventEnvelope> received = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);

        bus.subscribe("teste", Set.of(Topic.CHAT), QueuePolicy.dropOldest(16), event -> {
            received.add(event);
            delivered.countDown();
        });

        bus.publish(EventType.CORE_STARTED, Map.of());
        bus.publish(EventType.USER_COMMAND, Map.of("text", "oi"));

        assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().type()).isEqualTo(EventType.USER_COMMAND);
    }

    @AcceptanceCriteria("SPEC-002/CA-6")
    @Test
    void publicarNaoBloqueiaQuandoOAssinanteNaoConsome() {
        EventSubscription subscription =
                bus.subscribe("parado", Set.of(Topic.SYSTEM), QueuePolicy.dropOldest(2), event -> park());

        for (int i = 0; i < 50; i++) {
            bus.publish(EventType.SYSTEM_ALERT, Map.of("i", i));
        }

        assertThat(bus.lastSeq()).isEqualTo(50);
        assertThat(subscription.droppedCount()).isPositive();
        assertThat(subscription.consumeGapFlag()).isTrue();
    }

    @AcceptanceCriteria("SPEC-002/CA-7")
    @Test
    void filaQueNaoAdmiteDescarteFalhaAltoEmVezDeSilenciar() {
        // É a política dos tópicos permission, security e change: perder um pedido de
        // autorização em silêncio é pior do que quebrar (ADR-0011).
        bus.subscribe("critico", Set.of(Topic.SYSTEM), QueuePolicy.rejectPublish(1), event -> park());

        assertThatThrownBy(() -> {
                    for (int i = 0; i < 10; i++) {
                        bus.publish(EventType.SYSTEM_ALERT, Map.of("i", i));
                    }
                })
                .isInstanceOf(EventQueueFullException.class);
    }

    @AcceptanceCriteria("SPEC-002/CA-8")
    @Test
    void replayDevolveApenasOQueVeioDepoisDoPontoPedido() {
        bus.publish(EventType.CORE_STARTED, Map.of());
        bus.publish(EventType.SYSTEM_ALERT, Map.of("n", 2));
        bus.publish(EventType.SYSTEM_ALERT, Map.of("n", 3));

        List<EventEnvelope> replayed = bus.replay(1, 100);

        assertThat(replayed).extracting(EventEnvelope::seq).containsExactly(2L, 3L);
        assertThat(bus.canReplayFrom(1)).isTrue();
    }

    @Test
    void topicoInexistenteNaoPodeSerAssinado() {
        assertThatThrownBy(() -> bus.subscribe("x", Set.of("inventado"), QueuePolicy.dropOldest(1), event -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Simula um assinante travado — uma UI congelada, na prática. */
    private static void park() {
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
