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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.api.trace.Spec;

/**
 * Barramento interno do núcleo (ADR-0011). Tudo o que acontece no Zordon passa por
 * aqui; o ZWP é a projeção dele para os clientes.
 *
 * <p>Três garantias: publicar nunca bloqueia, cada assinante tem fila própria, e
 * todo evento recebe um {@code seq} monotônico dentro de uma inicialização. O
 * terceiro é o que torna a reconexão correta por construção.
 */
@Spec("SPEC-002")
public final class ZordonEventBus {

    /** Anel de replay: o menor entre 2.000 eventos e 5 minutos (ADR-0011). */
    public static final int REPLAY_CAPACITY = 2_000;

    public static final Duration REPLAY_WINDOW = Duration.ofMinutes(5);

    private final String startId;
    private final Clock clock;
    private final AtomicLong seq = new AtomicLong();
    private final Deque<EventEnvelope> ring = new ArrayDeque<>(REPLAY_CAPACITY);
    private final List<EventSubscription> subscriptions = new CopyOnWriteArrayList<>();

    public ZordonEventBus(String startId) {
        this(startId, Clock.systemUTC());
    }

    public ZordonEventBus(String startId, Clock clock) {
        this.startId = startId;
        this.clock = clock;
    }

    public String startId() {
        return startId;
    }

    public long lastSeq() {
        return seq.get();
    }

    /** Publica um evento. Não bloqueia e não falha por assinante lento. */
    public EventEnvelope publish(EventType type, Map<String, Object> payload) {
        EventEnvelope event = new EventEnvelope(seq.incrementAndGet(), Instant.now(clock), type, payload);
        remember(event);
        subscriptions.stream().filter(subscription -> subscription.wants(event)).forEach(s -> s.offer(event));
        return event;
    }

    public EventSubscription subscribe(
            String name, Set<String> topics, QueuePolicy policy, Consumer<EventEnvelope> listener) {
        topics.stream().filter(topic -> !Topic.exists(topic)).findFirst().ifPresent(topic -> {
            throw new IllegalArgumentException("tópico inexistente: " + topic);
        });
        EventSubscription subscription = new EventSubscription(name, topics, policy, listener);
        subscriptions.add(subscription);
        return subscription;
    }

    public void unsubscribe(EventSubscription subscription) {
        subscriptions.remove(subscription);
        subscription.close();
    }

    /**
     * Eventos posteriores a {@code fromSeq} que ainda estão no anel.
     *
     * @return vazio quando {@code fromSeq} é velho demais — o chamador precisa
     *     distinguir isso de "nada aconteceu" com {@link #canReplayFrom(long)}.
     */
    public synchronized List<EventEnvelope> replay(long fromSeq, int limit) {
        List<EventEnvelope> result = new ArrayList<>();
        for (EventEnvelope event : ring) {
            if (event.seq() > fromSeq && result.size() < limit) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }

    /** Se o anel ainda cobre {@code fromSeq}, a retomada é possível. */
    public synchronized boolean canReplayFrom(long fromSeq) {
        if (fromSeq > seq.get()) {
            return false;
        }
        EventEnvelope oldest = ring.peekFirst();
        return oldest == null || oldest.seq() <= fromSeq + 1;
    }

    public void close() {
        subscriptions.forEach(EventSubscription::close);
        subscriptions.clear();
    }

    private synchronized void remember(EventEnvelope event) {
        ring.addLast(event);
        Instant cutoff = event.ts().minus(REPLAY_WINDOW);
        while (ring.size() > REPLAY_CAPACITY
                || (!ring.isEmpty() && ring.peekFirst().ts().isBefore(cutoff))) {
            ring.removeFirst();
        }
    }
}
