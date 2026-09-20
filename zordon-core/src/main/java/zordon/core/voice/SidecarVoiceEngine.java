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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.EOFException;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;

/**
 * O motor de voz de verdade: o sidecar {@code zordon-voice} num socket Unix
 * (SPEC-011, SPEC-013, ADR-0028). O núcleo é o cliente e reconecta sozinho; enquanto o
 * socket não existe, o motor está "não instalado"; conectado, "carregando" até o
 * sidecar dizer que aqueceu os modelos.
 *
 * <p>Moldura: tamanho do cabeçalho (u32 big-endian), cabeçalho JSON e, se ele
 * tiver {@code bytes}, a carga. A mesma de {@code voice/zordon_voice/framing.py}.
 */
@Spec("SPEC-011")
public final class SidecarVoiceEngine implements VoiceEngine, AutoCloseable {

    public static final String NOT_INSTALLED = "motor de voz não instalado";
    public static final String LOADING = "o motor de voz está carregando";
    static final int MAX_HEADER = 64 * 1024;
    static final int MAX_PAYLOAD = 4 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(SidecarVoiceEngine.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final Path socket;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<Long, Listening> listens = new ConcurrentHashMap<>();
    private final Map<Long, Speech> speeches = new ConcurrentHashMap<>();
    private final Map<Long, Stream> streams = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private volatile Status status = new Status(State.ABSENT, NOT_INSTALLED);
    private volatile boolean wake;
    private volatile SocketChannel channel;
    private volatile boolean running;
    private Thread worker;

    public SidecarVoiceEngine(Path socket) {
        this.socket = Objects.requireNonNull(socket, "socket");
    }

    public void start() {
        running = true;
        worker = Thread.ofVirtual().name("zordon-voice-engine").start(this::loop);
    }

    @Override
    public Status status() {
        return status;
    }

    @Override
    public Activity activity() {
        return Activity.IDLE;
    }

    @Override
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public boolean wakeWord() {
        return wake && status.state() == State.READY;
    }

    @Override
    public boolean stream(long id, StreamMode mode, Stream listener) {
        if (status.state() != State.READY) {
            return false;
        }
        streams.put(id, listener);
        if (!send(Map.of("op", "stream", "id", id, "mode", mode.wire()), null)) {
            streams.remove(id);
            return false;
        }
        return true;
    }

    @Override
    public void duplex(long id, boolean speaking, boolean armed) {
        if (streams.containsKey(id)) {
            send(Map.of("op", "duplex", "id", id, "speaking", speaking, "armed", armed), null);
        }
    }

    @Override
    public void listenNow(long id) {
        if (streams.containsKey(id)) {
            send(Map.of("op", "listen_now", "id", id), null);
        }
    }

    @Override
    public boolean listen(long id, Listening listener) {
        if (status.state() != State.READY) {
            return false;
        }
        listens.put(id, listener);
        if (!send(Map.of("op", "listen", "id", id), null)) {
            listens.remove(id);
            return false;
        }
        return true;
    }

    @Override
    public void audio(long id, byte[] pcm) {
        if (listens.containsKey(id) || streams.containsKey(id)) {
            send(Map.of("op", "audio", "id", id), pcm);
        }
    }

    @Override
    public void stop(long id) {
        send(Map.of("op", "stop", "id", id), null);
    }

    @Override
    public void cancel(long id) {
        listens.remove(id);
        streams.remove(id);
        send(Map.of("op", "cancel", "id", id), null);
    }

    @Override
    public boolean speak(long id, String text, Speech speech) {
        if (status.state() != State.READY) {
            return false;
        }
        speeches.put(id, speech);
        if (!send(Map.of("op", "speak", "id", id, "text", text), null)) {
            speeches.remove(id);
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        running = false;
        SocketChannel current = channel;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                log.debug("fechando o socket do motor: {}", e.toString());
            }
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        long backoff = 500;
        while (running) {
            if (!Files.exists(socket)) {
                change(new Status(State.ABSENT, NOT_INSTALLED));
                sleep(Math.min(backoff *= 2, 5_000));
                continue;
            }
            try (SocketChannel connected = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                connected.connect(UnixDomainSocketAddress.of(socket));
                channel = connected;
                backoff = 500;
                change(new Status(State.STARTING, LOADING));
                log.info("conectado ao motor de voz em {}", socket);
                while (running) {
                    handle(read(connected));
                }
            } catch (IOException e) {
                log.debug("motor de voz indisponível: {}", e.toString());
            } finally {
                channel = null;
                failPending();
            }
            if (running) {
                change(new Status(Files.exists(socket) ? State.STARTING : State.ABSENT,
                        Files.exists(socket) ? LOADING : NOT_INSTALLED));
                sleep(Math.min(backoff *= 2, 5_000));
            }
        }
    }

    private void handle(Message message) {
        Map<?, ?> header = message.header();
        long id = header.get("id") instanceof Number number ? number.longValue() : -1;
        switch (String.valueOf(header.get("ev"))) {
            case "state" -> {
                boolean hasWake = Boolean.TRUE.equals(header.get("wake"));
                boolean wakeChanged = hasWake != wake;
                wake = hasWake;
                boolean changed = change(switch (String.valueOf(header.get("state"))) {
                    case "ready" -> new Status(State.READY, null);
                    case "failed" -> new Status(State.FAILED, "o motor de voz falhou: " + header.get("reason"));
                    default -> new Status(State.STARTING, LOADING);
                });
                if (wakeChanged && !changed) {
                    listeners.forEach(Runnable::run);
                }
            }
            case "wake" -> {
                Stream stream = streams.get(id);
                if (stream != null) {
                    stream.wake(header.get("score") instanceof Number score ? score.doubleValue() : 0);
                }
            }
            case "speech" -> {
                Listening listening = listens.get(id);
                if (listening != null) {
                    listening.speech();
                }
                Stream stream = streams.get(id);
                if (stream != null) {
                    stream.speech();
                }
            }
            case "end" -> {
                Listening listening = listens.get(id);
                if (listening != null) {
                    listening.ended();
                }
                Stream stream = streams.get(id);
                if (stream != null) {
                    stream.ended();
                }
            }
            case "final" -> {
                Transcript transcript = new Transcript(
                        header.get("text") instanceof String text ? text : "",
                        header.get("confidence") instanceof Number c ? c.doubleValue() : 0,
                        header.get("durationMs") instanceof Number d ? d.longValue() : 0,
                        header.get("reason") instanceof String reason ? reason : "end");
                Listening listening = listens.remove(id);
                if (listening != null) {
                    listening.transcript(transcript);
                }
                Stream stream = streams.get(id);
                if (stream != null) {
                    stream.transcript(transcript);
                }
            }
            case "stream_end" -> {
                Stream stream = streams.remove(id);
                if (stream != null) {
                    stream.closed(header.get("reason") instanceof String reason ? reason : "lost");
                }
            }
            case "tts" -> {
                Speech speech = speeches.get(id);
                if (speech != null) {
                    speech.chunk(header.get("rate") instanceof Number rate ? rate.intValue() : 22_050,
                            message.payload());
                }
            }
            case "tts_end" -> {
                Speech speech = speeches.remove(id);
                if (speech != null) {
                    speech.end(header.get("reason") instanceof String reason ? reason : null);
                }
            }
            default -> log.debug("evento desconhecido do motor: {}", header.get("ev"));
        }
    }

    /** Escuta e fala em curso não têm mais quem as termine: terminam aqui, como perdidas. */
    private void failPending() {
        listens.keySet().forEach(id -> {
            Listening listening = listens.remove(id);
            if (listening != null) {
                listening.transcript(new Transcript("", 0, 0, "lost"));
            }
        });
        speeches.keySet().forEach(id -> {
            Speech speech = speeches.remove(id);
            if (speech != null) {
                speech.end("lost");
            }
        });
        streams.keySet().forEach(id -> {
            Stream stream = streams.remove(id);
            if (stream != null) {
                stream.closed("lost");
            }
        });
        wake = false;
    }

    private boolean change(Status next) {
        if (next.equals(status)) {
            return false;
        }
        status = next;
        log.info("motor de voz: {}{}{}", next.state().wire(), next.reason() == null ? "" : " — " + next.reason(),
                next.state() == State.READY ? (wake ? ", com palavra de ativação" : ", sem palavra de ativação") : "");
        listeners.forEach(Runnable::run);
        return true;
    }

    private boolean send(Map<String, Object> header, byte[] payload) {
        SocketChannel current = channel;
        if (current == null) {
            return false;
        }
        try {
            ByteBuffer frame = encode(header, payload);
            synchronized (writeLock) {
                while (frame.hasRemaining()) {
                    current.write(frame);
                }
            }
            return true;
        } catch (IOException e) {
            log.debug("mensagem ao motor não enviada: {}", e.toString());
            return false;
        }
    }

    record Message(Map<?, ?> header, byte[] payload) {}

    static ByteBuffer encode(Map<String, Object> header, byte[] payload) throws IOException {
        Map<String, Object> full = new LinkedHashMap<>(header);
        if (payload != null && payload.length > 0) {
            full.put("bytes", payload.length);
        }
        byte[] raw = json.writeValueAsBytes(full);
        int size = payload == null ? 0 : payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(4 + raw.length + size);
        buffer.putInt(raw.length).put(raw);
        if (size > 0) {
            buffer.put(payload);
        }
        return buffer.flip();
    }

    static Message read(java.nio.channels.ReadableByteChannel channel) throws IOException {
        int size = readFully(channel, 4).getInt();
        if (size <= 0 || size > MAX_HEADER) {
            throw new IOException("cabeçalho de " + size + " bytes do motor");
        }
        Map<?, ?> header = json.readValue(new String(readFully(channel, size).array(), StandardCharsets.UTF_8),
                Map.class);
        int bytes = header.get("bytes") instanceof Number number ? number.intValue() : 0;
        if (bytes < 0 || bytes > MAX_PAYLOAD) {
            throw new IOException("carga de " + bytes + " bytes do motor");
        }
        return new Message(header, bytes == 0 ? new byte[0] : readFully(channel, bytes).array());
    }

    private static ByteBuffer readFully(java.nio.channels.ReadableByteChannel channel, int size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException("o motor fechou a conexão");
            }
        }
        return buffer.flip();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
