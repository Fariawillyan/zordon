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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.api.zwp.BinaryFrame;
import zordon.core.zwp.BinaryHandler;
import zordon.core.zwp.ClientNotifier;
import zordon.core.zwp.ZwpSession;

/**
 * Onde o áudio do host chega (SPEC-009 §5). Aceita só o stream anunciado, da
 * sessão do host ativo; mede o nível; devolve crédito; e descarta o áudio.
 */
@Spec("SPEC-009")
public final class AudioIngest implements BinaryHandler {

    /** Crédito devolvido a cada 10 frames (200 ms). */
    static final int CREDIT_BATCH = 10;
    /** No máximo 20 {@code VOICE_LEVEL} por segundo. */
    static final long LEVEL_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    static final long ALERT_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    /** Abaixo disto o frame não conta como "com sinal" na média do teste. */
    static final double VOICED_DBFS = -60;
    private static final int RETIRED_KEPT = 16;

    /** Resumo do que chegou desde o último {@link #resetStats()}. */
    public record Stats(long frames, double peakDbfs, double averageDbfs) {}

    private static final Logger log = LoggerFactory.getLogger(AudioIngest.class);

    private final ClientNotifier clients;
    private final Consumer<Map<String, Object>> levels;
    private final Consumer<Map<String, Object>> alerts;
    private final LongSupplier nanos;

    // Protegidos por this.
    private String session;
    private int stream = -1;
    private int unacked;
    private boolean levelsWanted;
    /** Para onde vai o áudio aceito durante uma escuta (o motor de voz); {@code null} descarta. */
    private java.util.function.Consumer<byte[]> forward;
    private long lastLevel;
    private boolean levelPublished;
    private long frames;
    private double peak;
    private long voiced;
    private double voicedRms;
    /** Streams encerrados: frames atrasados deles caem em silêncio, sem alerta. */
    private final Deque<String> retired = new ArrayDeque<>();
    private final Map<String, Long> lastAlert = new HashMap<>();

    public AudioIngest(
            ClientNotifier clients,
            Consumer<Map<String, Object>> levels,
            Consumer<Map<String, Object>> alerts,
            LongSupplier nanos) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.levels = Objects.requireNonNull(levels, "levels");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.nanos = Objects.requireNonNull(nanos, "nanos");
    }

    /** Um ingest que só mede: para quem não precisa de rede nem de eventos. */
    public static AudioIngest detached() {
        return new AudioIngest((session, method, params) -> false, level -> {}, alert -> {}, System::nanoTime);
    }

    /** Passa a aceitar {@code streamId} vindo de {@code sessionId}; o anterior se encerra. */
    public synchronized void open(String sessionId, int streamId) {
        close();
        session = sessionId;
        stream = streamId;
        unacked = 0;
        log.debug("stream de áudio {} aberto para a sessão {}", streamId, sessionId);
    }

    public synchronized void close() {
        if (stream < 0) {
            return;
        }
        retired.addLast(session + "/" + stream);
        while (retired.size() > RETIRED_KEPT) {
            retired.removeFirst();
        }
        log.debug("stream de áudio {} fechado", stream);
        session = null;
        stream = -1;
    }

    /** Repassa o áudio aceito a {@code target} (SPEC-011); {@code null} volta a descartar. */
    public synchronized void forward(java.util.function.Consumer<byte[]> target) {
        forward = target;
    }

    /** Liga a publicação de {@code VOICE_LEVEL}: só no teste ou na escuta (SPEC-009 §3). */
    public synchronized void levels(boolean wanted) {
        levelsWanted = wanted;
    }

    public synchronized void resetStats() {
        frames = 0;
        peak = 0;
        voiced = 0;
        voicedRms = 0;
    }

    public synchronized Stats stats() {
        return new Stats(
                frames,
                AudioLevel.dbfs(peak),
                voiced == 0 ? AudioLevel.FLOOR_DBFS : AudioLevel.dbfs(voicedRms / voiced));
    }

    @Override
    public void frame(ZwpSession from, BinaryFrame frame) {
        accept(from.id(), frame);
    }

    @Override
    public void malformed(ZwpSession from, String reason) {
        reject(from.id(), -1, "frame binário inválido: " + reason);
    }

    synchronized void accept(String sessionId, BinaryFrame frame) {
        boolean current = sessionId.equals(session) && frame.streamId() == stream;
        if (!current) {
            if (!retired.contains(sessionId + "/" + frame.streamId())) {
                reject(sessionId, frame.streamId(), "frame de áudio de stream não anunciado");
            }
            return;
        }
        switch (frame.type()) {
            case AUDIO_IN -> consume(frame);
            case AUDIO_END -> log.debug("host encerrou o stream {}", stream);
            default -> reject(sessionId, frame.streamId(), "frame " + frame.type() + " não esperado no stream de áudio");
        }
    }

    private void consume(BinaryFrame frame) {
        if (forward != null) {
            forward.accept(frame.payload());
        }
        AudioLevel level = AudioLevel.of(frame.payload());
        frames++;
        peak = Math.max(peak, level.peak());
        if (level.rmsDbfs() > VOICED_DBFS) {
            voiced++;
            voicedRms += level.rms();
        }
        if (++unacked >= CREDIT_BATCH) {
            clients.notify(session, "audio.credit", Map.of("streamId", stream, "frames", unacked));
            unacked = 0;
        }
        long now = nanos.getAsLong();
        if (levelsWanted && (!levelPublished || now - lastLevel >= LEVEL_INTERVAL_NANOS)) {
            levelPublished = true;
            lastLevel = now;
            levels.accept(Map.of("rms", level.rmsDbfs(), "peak", level.peakDbfs(),
                    "bass", levelDbfs(level.bass()), "mid", levelDbfs(level.mid()),
                    "treble", levelDbfs(level.treble())));
        }
    }

    private static double levelDbfs(double level) {
        return AudioLevel.dbfs(level);
    }

    /** Um alerta por sessão por minuto: um cliente defeituoso não inunda o tópico. */
    private void reject(String sessionId, int streamId, String message) {
        long now = nanos.getAsLong();
        Long previous = lastAlert.get(sessionId);
        if (previous != null && now - previous < ALERT_INTERVAL_NANOS) {
            return;
        }
        lastAlert.put(sessionId, now);
        log.warn("{} (sessão {}, stream {})", message, sessionId, streamId);
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("message", message);
        alert.put("sessionId", sessionId);
        alert.put("streamId", streamId);
        alerts.accept(alert);
    }

}
