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
package zordon.zwp;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.zwp.EndpointFile;
import zordon.api.zwp.HelloResult;

/** A reconexão: descobre o endereço, conecta, espera a conexão cair e tenta de novo (ADR-0006, ZWP §8). */
final class ConnectionLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(CoreConnection.class);

    private final EndpointWatch endpoint;
    private final SessionResume resume;
    private final ActiveClient active;
    private final CoreConnection.Listener listener;
    private final AtomicReference<CoreConnection.State> state =
            new AtomicReference<>(CoreConnection.State.OFFLINE);
    private volatile boolean running = true;

    ConnectionLoop(EndpointWatch endpoint, SessionResume resume, ActiveClient active,
            CoreConnection.Listener listener) {
        this.endpoint = endpoint;
        this.resume = resume;
        this.active = active;
        this.listener = listener;
    }

    CoreConnection.State state() {
        return state.get();
    }

    void stop() {
        running = false;
    }

    @Override
    public void run() {
        while (running) {
            Optional<EndpointFile> found = endpoint.read();
            if (found.isEmpty()) {
                goOffline("núcleo não publicou endpoint.json");
                endpoint.waitBeforeRetry(() -> running);
                continue;
            }
            if (!tryConnect(found.get())) {
                endpoint.waitBeforeRetry(() -> running);
            }
        }
    }

    private boolean tryConnect(EndpointFile found) {
        state.set(CoreConnection.State.CONNECTING);
        for (String address : found.endpoints()) {
            if (!running) {
                return true;
            }
            ForwardingListener forwarding = new ForwardingListener(resume, listener);
            try (ZwpClient candidate = new ZwpClient(URI.create(address), found.token(), forwarding)) {
                active.bind(candidate);
                HelloResult hello = candidate.connect(resume.hello(), Duration.ofSeconds(10));
                active.set(candidate);
                state.set(CoreConnection.State.ONLINE);
                endpoint.connected();
                listener.onOnline(hello, resume.online(hello));
                forwarding.awaitClose();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            } catch (RuntimeException e) {
                log.debug("falha ao conectar em {}: {}", address, e.getMessage());
                continue;
            } finally {
                active.clear();
            }
            goOffline("conexão encerrada");
            return false;
        }
        goOffline("nenhum endereço do endpoint.json respondeu");
        return false;
    }

    private void goOffline(String reason) {
        if (state.getAndSet(CoreConnection.State.OFFLINE) != CoreConnection.State.OFFLINE) {
            listener.onOffline(reason);
        }
    }
}
