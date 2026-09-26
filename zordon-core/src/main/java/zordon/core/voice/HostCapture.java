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
package zordon.core.voice;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.core.zwp.ClientRequests;

/**
 * Os hosts do Windows e o microfone deles (SPEC-006 §5): quem controla, o que o
 * núcleo pediu e o que o host confirmou. {@code off} só é afirmado com
 * confirmação do host. Acessado sob o lock do {@link VoiceService}, menos onde
 * está dito.
 */
final class HostCapture {

    enum Capture {
        ON,
        OFF,
        PENDING,
        UNKNOWN
    }

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final ClientRequests clients;
    private final AudioIngest ingest;
    private final Clock clock;
    private final Object lock;
    /** Sessões de host em ordem de chegada; a última controla o microfone. */
    private final Deque<String> hosts = new ArrayDeque<>();
    /** Hosts que tocam áudio ({@code audio.playback}), em ordem de chegada. */
    private final Deque<String> playbackHosts = new ArrayDeque<>();
    private String deviceName;
    private Capture capture = Capture.UNKNOWN;
    private Boolean requested;
    private Instant confirmedAt;
    private String hostDisagrees;
    /** Só a resposta ao pedido mais recente conta; as anteriores chegam tarde demais. */
    private long generation;
    private int lastStream;

    HostCapture(ClientRequests clients, AudioIngest ingest, Clock clock, Object lock) {
        this.clients = clients;
        this.ingest = ingest;
        this.clock = clock;
        this.lock = lock;
    }

    String active() {
        return hosts.peekLast();
    }

    /** O host ativo, tomando o lock: para quem chama de fora dele. */
    String activeLocked() {
        synchronized (lock) {
            return hosts.peekLast();
        }
    }

    boolean connected() {
        return !hosts.isEmpty();
    }

    boolean isActive(String sessionId) {
        return sessionId.equals(hosts.peekLast());
    }

    /** Um host chegou e passa a controlar o microfone; o estado do anterior é esquecido. */
    void connect(String sessionId) {
        String previous = hosts.peekLast();
        hosts.remove(sessionId);
        hosts.addLast(sessionId);
        if (previous != null && !previous.equals(sessionId)) {
            // Dois hosts: o mais recente controla, e o anterior larga o microfone.
            clients.request(previous, "audio.setCaptureEnabled", Map.of("enabled", false),
                    VoiceService.CAPTURE_TIMEOUT)
                    .exceptionally(failure -> {
                        log.warn("host anterior não confirmou que largou o microfone: {}", message(failure));
                        return Map.of();
                    });
        }
        forget();
    }

    boolean remove(String sessionId) {
        return hosts.remove(sessionId);
    }

    void playbackConnected(String sessionId) {
        playbackHosts.remove(sessionId);
        playbackHosts.addLast(sessionId);
    }

    void playbackGone(String sessionId) {
        playbackHosts.remove(sessionId);
    }

    /** O host que toca a fala, o mais recente. */
    Optional<String> playback() {
        return Optional.ofNullable(playbackHosts.peekLast());
    }

    void forget() {
        ingest.close();
        generation++;
        capture = Capture.UNKNOWN;
        requested = null;
        confirmedAt = null;
        deviceName = null;
        hostDisagrees = null;
    }

    boolean needsRequest(boolean wanted) {
        return requested == null || requested != wanted;
    }

    /** Marca o pedido como pendente. @return o número dele: só a resposta deste conta */
    long pending(boolean enabled) {
        long request = ++generation;
        requested = enabled;
        capture = Capture.PENDING;
        hostDisagrees = null;
        return request;
    }

    CompletableFuture<Map<String, Object>> send(String host, boolean enabled) {
        Map<String, Object> params;
        if (enabled) {
            // Cada captura é um stream novo: frame de captura anterior não entra nesta.
            lastStream = lastStream % 0xFFFF + 1;
            ingest.open(host, lastStream);
            params = Map.of("enabled", true, "streamId", lastStream);
        } else {
            ingest.close();
            params = Map.of("enabled", false);
        }
        return clients.request(host, "audio.setCaptureEnabled", params, VoiceService.CAPTURE_TIMEOUT);
    }

    boolean current(long request) {
        return request == generation;
    }

    /**
     * O que o host respondeu ao pedido mais recente.
     *
     * @return o motivo, quando o host não fez o que foi pedido; {@code null} quando fez ou não respondeu
     */
    String answered(boolean enabled, Map<String, Object> result, Throwable failure) {
        if (failure != null) {
            capture = Capture.UNKNOWN;
            confirmedAt = null;
            // Sem confirmação, o próximo reconcile pede de novo.
            requested = null;
            log.warn("o host não confirmou {} o microfone: {}", enabled ? "ligar" : "desligar", message(failure));
            return null;
        }
        boolean actual = Boolean.TRUE.equals(result.get("enabled"));
        capture = actual ? Capture.ON : Capture.OFF;
        confirmedAt = clock.instant();
        if (actual == enabled) {
            return null;
        }
        String base = actual ? "o host não desligou o microfone" : "o host não ligou o microfone";
        // O host sabe por quê (privacidade do Windows, dispositivo ocupado); SPEC-007 §7.
        hostDisagrees = result.get("reason") instanceof String reason && !reason.isBlank()
                ? base + ": " + reason
                : base;
        log.warn("{}", hostDisagrees);
        return hostDisagrees;
    }

    /** O microfone confirmado ligado, sem desacordo do host: é quando o fluxo contínuo pode abrir. */
    boolean confirmedOn() {
        return capture == Capture.ON && hostDisagrees == null && !hosts.isEmpty();
    }

    String disagrees() {
        return hostDisagrees;
    }

    CompletableFuture<Map<String, Object>> ask(String host, String method, Map<String, Object> params) {
        return clients.request(host, method, params, VoiceService.DEVICE_TIMEOUT);
    }

    void deviceName(Map<String, Object> result) {
        deviceName = result.get("name") instanceof String name ? name : null;
    }

    String captureName() {
        return capture.name().toLowerCase(Locale.ROOT);
    }

    Map<String, Object> captureState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("state", captureName());
        VoiceSnapshot.putIfPresent(state, "requested", requested);
        VoiceSnapshot.putIfPresent(state, "confirmedAt", confirmedAt);
        return state;
    }

    Map<String, Object> hostState() {
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("connected", !hosts.isEmpty());
        VoiceSnapshot.putIfPresent(host, "device", deviceName);
        return host;
    }

    static String message(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        return String.valueOf(cause.getMessage());
    }
}
