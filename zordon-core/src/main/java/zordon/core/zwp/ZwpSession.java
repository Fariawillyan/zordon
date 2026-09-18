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
package zordon.core.zwp;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import zordon.api.event.EventEnvelope;
import zordon.api.event.Topic;
import zordon.api.zwp.ClientInfo;

/** Estado de uma conexão ZWP autenticada. */
public final class ZwpSession {

    private final String id;
    private final Consumer<EventEnvelope> sink;
    private final AtomicReference<ClientInfo> client = new AtomicReference<>();
    private final AtomicReference<Runnable> afterResponse = new AtomicReference<>();
    private final Set<String> topics = ConcurrentHashMap.newKeySet();

    ZwpSession(String id, Consumer<EventEnvelope> sink) {
        this.id = id;
        this.sink = sink;
        // Segurança e alteração de projeto chegam mesmo sem o cliente pedir: um
        // cliente não pode optar por não saber (ADR-0014, ADR-0024).
        topics.addAll(Topic.MANDATORY);
    }

    public String id() {
        return id;
    }

    public Optional<ClientInfo> client() {
        return Optional.ofNullable(client.get());
    }

    public boolean helloCompleted() {
        return client.get() != null;
    }

    void completeHello(ClientInfo info) {
        client.set(info);
    }

    public Set<String> topics() {
        return Set.copyOf(topics);
    }

    public boolean subscribedTo(String topic) {
        return topics.contains(topic);
    }

    public Set<String> subscribe(Set<String> requested) {
        requested.stream().filter(topic -> !Topic.exists(topic)).findFirst().ifPresent(topic -> {
            throw new IllegalArgumentException("tópico inexistente: " + topic);
        });
        topics.addAll(requested);
        return topics();
    }

    /** Entrega o evento se a sessão assina o tópico dele. */
    public void deliver(EventEnvelope event) {
        if (helloCompleted() && subscribedTo(event.topic())) {
            sink.accept(event);
        }
    }

    /**
     * Agenda algo para depois da resposta desta requisição. É como o replay chega
     * ao cliente sem ultrapassar o resultado do {@code session.hello} no socket.
     */
    public void runAfterResponse(Runnable action) {
        afterResponse.set(action);
    }

    Runnable takePendingAction() {
        return afterResponse.getAndSet(null);
    }

    public Set<String> unsubscribe(Set<String> requested) {
        requested.stream().filter(Topic.MANDATORY::contains).findFirst().ifPresent(topic -> {
            throw new IllegalArgumentException("tópico obrigatório não pode ser desassinado: " + topic);
        });
        topics.removeAll(requested);
        return topics();
    }
}
