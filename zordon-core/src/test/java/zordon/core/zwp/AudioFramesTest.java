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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.FrameType;
import zordon.api.zwp.HelloParams;
import zordon.api.zwp.HelloResult;
import zordon.core.CoreUnderTest;
import zordon.zwp.ZwpClient;
import zordon.zwp.ZwpClientListener;

/** Frames de áudio de verdade, por WebSocket, de um host de teste até o nível no desktop. */
class AudioFramesTest {

    @TempDir
    Path home;

    private final List<EventEnvelope> events = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> credits = new CopyOnWriteArrayList<>();
    private final AtomicInteger stream = new AtomicInteger(-1);
    private final AtomicBoolean capturing = new AtomicBoolean();

    @AcceptanceCriteria("SPEC-009/CA-7")
    @Test
    @SuppressWarnings("unchecked")
    void testeDoMicrofoneLevaONivelDoHostAteODesktop() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient desktop = client(core, new ZwpClientListener() {
                    @Override
                    public void onEvent(EventEnvelope event) {
                        events.add(event);
                    }
                });
                ZwpClient host = client(core, new ZwpClientListener() {})) {
            host.onNotification("audio.credit", credits::add);
            host.handle("audio.setCaptureEnabled", params -> {
                boolean enabled = Boolean.TRUE.equals(params.get("enabled"));
                if (enabled) {
                    stream.set(((Number) params.get("streamId")).intValue());
                    capturing.set(true);
                    Thread.ofVirtual().start(() -> speak(host));
                } else {
                    capturing.set(false);
                }
                return Map.of("enabled", enabled);
            });
            HelloResult hello = host.connect(
                    HelloParams.of(new ClientInfo(ClientKind.HOST, "host-de-teste", "0"), List.of("audio.capture")),
                    Duration.ofSeconds(10));
            desktop.connect(HelloParams.of(new ClientInfo(ClientKind.DESKTOP, "teste", "0"), List.of()),
                    Duration.ofSeconds(10));
            desktop.request("session.subscribe", Map.of("topics", List.of("voice"))).get(5, TimeUnit.SECONDS);

            assertThat(hello.audioCreditFrames()).isEqualTo(50);

            Map<String, Object> started = desktop.request("voice.testMicrophone", Map.of("seconds", 1))
                    .get(5, TimeUnit.SECONDS);
            assertThat(started).containsKey("test");

            await(() -> events.stream().anyMatch(event -> event.type() == EventType.VOICE_STATE
                    && event.payload().get("lastTest") != null));

            List<EventEnvelope> levels = events.stream().filter(event -> event.type() == EventType.VOICE_LEVEL).toList();
            assertThat(levels).isNotEmpty().hasSizeLessThanOrEqualTo(25);
            assertThat(levels.getFirst().payload()).containsOnlyKeys("rms", "peak", "bass", "mid", "treble");
            Map<String, Object> lastTest = (Map<String, Object>) events.stream()
                    .filter(event -> event.payload().get("lastTest") != null).findFirst().orElseThrow()
                    .payload().get("lastTest");
            assertThat(lastTest).containsEntry("verdict", "ok");
            // Crédito conforme consome (CA-6): um a cada 10 frames aceitos. Ele volta
            // pela conexão do host, sem ordem garantida em relação ao VOICE_STATE que
            // chegou ao desktop — por isso espera. E a conta sai dos frames que o
            // núcleo diz ter consumido: num runner carregado o host pode mandar menos
            // de 10 no segundo do teste, e aí nenhum crédito é o certo.
            int consumed = ((Number) lastTest.get("frames")).intValue();
            await(() -> credits.size() >= consumed / 10);
            assertThat(credits).allSatisfy(credit -> assertThat(credit)
                    .containsEntry("streamId", stream.get()).containsEntry("frames", 10));
            await(() -> !capturing.get());
        }
    }

    @AcceptanceCriteria("SPEC-009/CA-2")
    @Test
    void oHelloAnunciaOCreditoDeAudio() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient host = client(core, new ZwpClientListener() {})) {
            HelloResult hello = host.connect(
                    HelloParams.of(new ClientInfo(ClientKind.HOST, "host-de-teste", "0"), List.of("audio.capture")),
                    Duration.ofSeconds(10));

            assertThat(hello.audioCreditFrames()).isEqualTo(50);
        }
    }

    @AcceptanceCriteria("SPEC-009/CA-5")
    @Test
    void frameDeStreamNaoAnunciadoGeraAlerta() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient desktop = client(core, new ZwpClientListener() {
                    @Override
                    public void onEvent(EventEnvelope event) {
                        events.add(event);
                    }
                });
                ZwpClient intruder = client(core, new ZwpClientListener() {})) {
            desktop.connect(HelloParams.of(new ClientInfo(ClientKind.DESKTOP, "teste", "0"), List.of()),
                    Duration.ofSeconds(10));
            desktop.request("session.subscribe", Map.of("topics", List.of("system"))).get(5, TimeUnit.SECONDS);
            intruder.connect(HelloParams.of(new ClientInfo(ClientKind.TEST, "intruso", "0"), List.of()),
                    Duration.ofSeconds(10));

            intruder.sendBinary(new BinaryFrame(FrameType.AUDIO_IN, 999, 0, new byte[640]));

            await(() -> events.stream().anyMatch(event -> event.type() == EventType.SYSTEM_ALERT
                    && Integer.valueOf(999).equals(event.payload().get("streamId"))));
        }
    }

    /** O "microfone" do host de teste: um seno a -20 dBFS em frames de 20 ms. */
    private void speak(ZwpClient host) {
        byte[] sine = new byte[640];
        for (int i = 0; i < 320; i++) {
            int sample = (int) Math.round(0.1 * 32767 * Math.sin(2 * Math.PI * 1000 * i / 16_000.0));
            sine[2 * i] = (byte) sample;
            sine[2 * i + 1] = (byte) (sample >> 8);
        }
        long seq = 0;
        while (capturing.get() && host.isOpen()) {
            host.sendBinary(new BinaryFrame(FrameType.AUDIO_IN, stream.get(), seq++, sine));
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
        host.sendBinary(BinaryFrame.end(stream.get(), seq));
    }

    private static ZwpClient client(CoreUnderTest core, ZwpClientListener listener) {
        return new ZwpClient(URI.create(core.address()), core.endpoint().token(), listener);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condição não atingida em 10 s");
            }
            Thread.sleep(20);
        }
    }
}
