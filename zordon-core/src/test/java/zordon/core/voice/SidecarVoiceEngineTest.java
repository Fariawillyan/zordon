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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;

/** O motor de voz contra um sidecar falso no socket Unix (SPEC-011). */
class SidecarVoiceEngineTest {

    @TempDir
    Path dir;

    @AcceptanceCriteria("SPEC-011/CA-1")
    @Test
    void oEstadoSegueOSidecarDoSocketAteOsModelosProntos() throws Exception {
        Path socket = dir.resolve("voice.sock");
        try (SidecarVoiceEngine engine = new SidecarVoiceEngine(socket)) {
            engine.start();
            await(() -> engine.status().state() == VoiceEngine.State.ABSENT);
            assertThat(engine.status().reason()).isEqualTo(SidecarVoiceEngine.NOT_INSTALLED);

            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                server.bind(UnixDomainSocketAddress.of(socket));
                SocketChannel sidecar = server.accept();
                await(() -> engine.status().state() == VoiceEngine.State.STARTING);
                assertThat(engine.status().reason()).isEqualTo(SidecarVoiceEngine.LOADING);

                write(sidecar, Map.of("ev", "state", "state", "ready"), null);
                await(() -> engine.status().state() == VoiceEngine.State.READY);

                sidecar.close();
                await(() -> engine.status().state() != VoiceEngine.State.READY);
            }
        }
    }

    @AcceptanceCriteria("SPEC-011/CA-2")
    @Test
    void escutaEFalaVaoEVoltamPeloSocket() throws Exception {
        Path socket = dir.resolve("voice.sock");
        List<String> events = new CopyOnWriteArrayList<>();
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                SidecarVoiceEngine engine = new SidecarVoiceEngine(socket)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            engine.start();
            SocketChannel sidecar = server.accept();
            write(sidecar, Map.of("ev", "state", "state", "ready"), null);
            await(() -> engine.status().state() == VoiceEngine.State.READY);

            assertThat(engine.listen(1, new VoiceEngine.Listening() {
                @Override public void speech() { events.add("speech"); }
                @Override public void ended() { events.add("end"); }
                @Override public void transcript(VoiceEngine.Transcript t) { events.add("final " + t.text()); }
            })).isTrue();
            engine.audio(1, new byte[640]);
            SidecarVoiceEngine.Message listen = SidecarVoiceEngine.read(sidecar);
            SidecarVoiceEngine.Message audio = SidecarVoiceEngine.read(sidecar);
            assertThat(header(listen)).containsEntry("op", "listen").containsEntry("id", 1);
            assertThat(header(audio)).containsEntry("op", "audio").containsEntry("bytes", 640);
            assertThat(audio.payload()).hasSize(640);

            write(sidecar, Map.of("ev", "speech", "id", 1), null);
            write(sidecar, Map.of("ev", "end", "id", 1), null);
            write(sidecar, Map.of("ev", "final", "id", 1, "text", "que horas são", "confidence", 0.9,
                    "durationMs", 1200, "reason", "end"), null);
            await(() -> events.size() == 3);
            assertThat(events).containsExactly("speech", "end", "final que horas são");

            ByteBuffer played = ByteBuffer.allocate(20);
            assertThat(engine.speak(2, "São 15h40.", new VoiceEngine.Speech() {
                @Override public void chunk(int rate, byte[] pcm) { played.put(pcm); events.add("rate " + rate); }
                @Override public void end(String reason) { events.add("tts_end"); }
            })).isTrue();
            assertThat(header(SidecarVoiceEngine.read(sidecar))).containsEntry("op", "speak")
                    .containsEntry("text", "São 15h40.")
                    .containsEntry("style", "normal");
            write(sidecar, Map.of("ev", "tts", "id", 2, "rate", 22050), new byte[20]);
            write(sidecar, Map.of("ev", "tts_end", "id", 2), null);
            await(() -> events.contains("tts_end"));
            assertThat(events).contains("rate 22050");
            assertThat(played.position()).isEqualTo(20);
        }
    }

    @AcceptanceCriteria("SPEC-011/CA-9")
    @Test
    void oJavaLeEEscreveAMesmaMolduraDoPython() throws Exception {
        Path golden = Path.of(System.getProperty("zordon.repoRoot", "..")).resolve("voice/tests/golden");

        SidecarVoiceEngine.Message fin = SidecarVoiceEngine.read(Channels.newChannel(
                Files.newInputStream(golden.resolve("final.bin"))));
        SidecarVoiceEngine.Message tts = SidecarVoiceEngine.read(Channels.newChannel(
                Files.newInputStream(golden.resolve("tts.bin"))));

        assertThat(header(fin)).containsEntry("text", "que horas são").containsEntry("durationMs", 1840);
        assertThat(tts.payload()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("ev", "final");
        header.put("id", 7);
        header.put("text", "que horas são");
        header.put("confidence", 0.95);
        header.put("durationMs", 1840);
        header.put("reason", "end");
        ByteBuffer java = SidecarVoiceEngine.encode(header, null);
        byte[] bytes = new byte[java.remaining()];
        java.get(bytes);
        assertThat(bytes).isEqualTo(Files.readAllBytes(golden.resolve("final.bin")));
    }

    private static Map<Object, Object> header(SidecarVoiceEngine.Message message) {
        return new java.util.HashMap<>(message.header());
    }

    private static void write(SocketChannel channel, Map<String, Object> header, byte[] payload) throws Exception {
        ByteBuffer frame = SidecarVoiceEngine.encode(header, payload);
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
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
