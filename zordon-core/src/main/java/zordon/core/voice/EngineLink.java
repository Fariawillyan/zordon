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

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A conexão com o sidecar: reconecta sozinha, lê os eventos e mantém o estado do
 * motor. Enquanto o socket não existe, o motor está "não instalado"; conectado,
 * "carregando" até o sidecar dizer que aqueceu os modelos.
 */
final class EngineLink {

    private static final Logger log = LoggerFactory.getLogger(SidecarVoiceEngine.class);

    private final Path socket;
    private final PendingVoiceCalls calls;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Object writeLock = new Object();
    private volatile VoiceEngine.Status status =
            new VoiceEngine.Status(VoiceEngine.State.ABSENT, SidecarVoiceEngine.NOT_INSTALLED);
    private volatile boolean wake;
    private volatile SocketChannel channel;
    private volatile boolean running;
    private Thread worker;

    EngineLink(Path socket, PendingVoiceCalls calls) {
        this.socket = socket;
        this.calls = calls;
    }

    void start() {
        running = true;
        worker = Thread.ofVirtual().name("zordon-voice-engine").start(this::loop);
    }

    VoiceEngine.Status status() {
        return status;
    }

    boolean ready() {
        return status.state() == VoiceEngine.State.READY;
    }

    boolean wakeWord() {
        return wake && ready();
    }

    void onChange(Runnable listener) {
        listeners.add(listener);
    }

    void close() {
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

    boolean send(Map<String, Object> header, byte[] payload) {
        SocketChannel current = channel;
        if (current == null) {
            return false;
        }
        try {
            ByteBuffer frame = EngineFrames.encode(header, payload);
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

    private void loop() {
        long backoff = 500;
        while (running) {
            if (!Files.exists(socket)) {
                change(new VoiceEngine.Status(VoiceEngine.State.ABSENT, SidecarVoiceEngine.NOT_INSTALLED));
                sleep(Math.min(backoff *= 2, 5_000));
                continue;
            }
            try (SocketChannel connected = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                connected.connect(UnixDomainSocketAddress.of(socket));
                channel = connected;
                backoff = 500;
                change(new VoiceEngine.Status(VoiceEngine.State.STARTING, SidecarVoiceEngine.LOADING));
                log.info("conectado ao motor de voz em {}", socket);
                while (running) {
                    handle(EngineFrames.read(connected));
                }
            } catch (IOException e) {
                log.debug("motor de voz indisponível: {}", e.toString());
            } finally {
                channel = null;
                calls.failAll();
                wake = false;
            }
            if (running) {
                change(new VoiceEngine.Status(Files.exists(socket) ? VoiceEngine.State.STARTING : VoiceEngine.State.ABSENT,
                        Files.exists(socket) ? SidecarVoiceEngine.LOADING : SidecarVoiceEngine.NOT_INSTALLED));
                sleep(Math.min(backoff *= 2, 5_000));
            }
        }
    }

    private void handle(EngineFrames.Message message) {
        Map<?, ?> header = message.header();
        String event = String.valueOf(header.get("ev"));
        if ("state".equals(event)) {
            state(header);
            return;
        }
        long id = header.get("id") instanceof Number number ? number.longValue() : -1;
        if (!calls.handle(event, id, header, message.payload())) {
            log.debug("evento desconhecido do motor: {}", header.get("ev"));
        }
    }

    private void state(Map<?, ?> header) {
        boolean hasWake = Boolean.TRUE.equals(header.get("wake"));
        boolean wakeChanged = hasWake != wake;
        wake = hasWake;
        boolean changed = change(switch (String.valueOf(header.get("state"))) {
            case "ready" -> new VoiceEngine.Status(VoiceEngine.State.READY, null);
            case "failed" -> new VoiceEngine.Status(VoiceEngine.State.FAILED, "o motor de voz falhou: " + header.get("reason"));
            default -> new VoiceEngine.Status(VoiceEngine.State.STARTING, SidecarVoiceEngine.LOADING);
        });
        if (wakeChanged && !changed) {
            listeners.forEach(Runnable::run);
        }
    }

    private boolean change(VoiceEngine.Status next) {
        if (next.equals(status)) {
            return false;
        }
        status = next;
        log.info("motor de voz: {}{}{}", next.state().wire(), next.reason() == null ? "" : " — " + next.reason(),
                next.state() == VoiceEngine.State.READY ? (wake ? ", com palavra de ativação" : ", sem palavra de ativação") : "");
        listeners.forEach(Runnable::run);
        return true;
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
