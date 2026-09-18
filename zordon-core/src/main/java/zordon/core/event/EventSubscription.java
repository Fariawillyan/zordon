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

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventEnvelope;

/**
 * Um assinante e a sua fila própria. Filas independentes são o que impede um
 * assinante lento de contaminar os outros
 * (docs/architecture/event-driven.md §4).
 */
public final class EventSubscription implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EventSubscription.class);

    private final Set<String> topics;
    private final QueuePolicy policy;
    private final Consumer<EventEnvelope> listener;
    private final BlockingQueue<EventEnvelope> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean gap = new AtomicBoolean();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Thread worker;

    EventSubscription(String name, Set<String> topics, QueuePolicy policy, Consumer<EventEnvelope> listener) {
        this.topics = Set.copyOf(topics);
        this.policy = policy;
        this.listener = listener;
        this.queue = new ArrayBlockingQueue<>(policy.capacity());
        this.worker = Thread.ofVirtual().name("zordon-events-" + name).start(this::drain);
    }

    boolean wants(EventEnvelope event) {
        return open.get() && topics.contains(event.topic());
    }

    /** Entrega à fila. Nunca bloqueia o produtor. */
    void offer(EventEnvelope event) {
        if (queue.offer(event)) {
            return;
        }
        switch (policy.overflow()) {
            case DROP_OLDEST -> {
                queue.poll();
                gap.set(true);
                dropped.incrementAndGet();
                if (!queue.offer(event)) {
                    dropped.incrementAndGet();
                }
            }
            case COALESCE -> {
                queue.removeIf(pending -> pending.type() == event.type());
                if (!queue.offer(event)) {
                    dropped.incrementAndGet();
                }
            }
            case REJECT_PUBLISH ->
                throw new EventQueueFullException(
                        "fila cheia em tópico que não admite descarte: " + event.topic());
        }
    }

    /** Eventos perdidos desde a assinatura. Em permission, security e change deve ser sempre zero. */
    public long droppedCount() {
        return dropped.get();
    }

    /** Consome e limpa a marca de descontinuidade, para o consumidor saber que perdeu algo. */
    public boolean consumeGapFlag() {
        return gap.getAndSet(false);
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            worker.interrupt();
        }
    }

    private void drain() {
        while (open.get()) {
            try {
                listener.accept(queue.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // Um assinante que explode não pode derrubar o barramento nem os outros.
                log.warn("assinante falhou ao consumir evento", e);
            }
        }
    }
}
