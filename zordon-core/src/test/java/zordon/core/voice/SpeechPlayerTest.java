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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.BinaryFrame;
import zordon.api.zwp.FrameType;
import zordon.core.activity.Narration;
import zordon.core.voice.VoiceFakes.Clients;
import zordon.core.voice.VoiceFakes.Engine;
import zordon.core.voice.VoiceFakes.MutableClock;

/** A fala do narrador tocando no host (SPEC-011 CA-4). */
class SpeechPlayerTest {

    @TempDir
    Path home;

    @AcceptanceCriteria("SPEC-011/CA-4")
    @Test
    void aFalaESintetizadaETocadaNoHostComPlayOutEEnd() throws Exception {
        Engine engine = new Engine();
        engine.ready();
        Clients clients = new Clients();
        clients.responder = call -> "audio.play".equals(call.method()) ? Map.of("accepted", true) : Map.of();
        List<Map<String, Object>> published = new CopyOnWriteArrayList<>();
        VoiceService voice = voice(engine, clients, published::add);
        voice.playbackHostConnected("h1");
        List<BinaryFrame> frames = new CopyOnWriteArrayList<>();
        SpeechPlayer player = new SpeechPlayer(engine, voice, clients, (session, frame) -> frames.add(frame));
        player.start();
        try {
            player.speak(new Narration("São 15h40.", Narration.Priority.HIGH, "resultado"));

            await(() -> !engine.speeches.isEmpty());
            VoiceEngine.Speech speech = engine.speeches.values().iterator().next();
            speech.chunk(22_050, new byte[8_820]);
            speech.end(null);
            await(() -> !frames.isEmpty() && frames.getLast().type() == FrameType.AUDIO_END);

            Map<String, Object> play = clients.last("audio.play").params();
            assertThat(play.get("format")).isEqualTo(Map.of("rate", 22_050, "channels", 1, "encoding", "s16le"));
            List<BinaryFrame> out = frames.stream().filter(f -> f.type() == FrameType.AUDIO_OUT).toList();
            assertThat(out.stream().mapToInt(f -> f.payload().length).sum()).isEqualTo(8_820);
            assertThat(frames).extracting(BinaryFrame::streamId).containsOnly((Integer) play.get("streamId"));
            assertThat(frames).extracting(BinaryFrame::seq).isSorted();
            assertThat(engine.calls).contains("speak São 15h40.");
            assertThat(published).anyMatch(snapshot -> "speaking".equals(snapshot.get("activity")));
        } finally {
            player.close();
        }
    }

    @Test
    void aFilaGuardaNoMaximoTresEAutorizacaoPassaNaFrente() {
        Engine engine = new Engine();
        VoiceService voice = voice(engine, new Clients(), snapshot -> { });
        SpeechPlayer player = new SpeechPlayer(engine, voice, new Clients(), (session, frame) -> true);

        for (int i = 0; i < 5; i++) {
            player.speak(new Narration("Etapa " + i + ".", Narration.Priority.NORMAL, "etapa"));
        }
        assertThat(player.queued()).isEqualTo(SpeechPlayer.QUEUE);

        player.speak(new Narration("Preciso da sua autorização para continuar.",
                Narration.Priority.AUTHORIZATION, "autorizacao"));
        assertThat(player.queued()).isEqualTo(1);
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

    /** Relógio falso: o prazo anda junto com o relógio do teste. */
    private VoiceService voice(Engine engine, Clients clients, Consumer<Map<String, Object>> published) {
        MutableClock clock = new MutableClock();
        return new VoiceService(new VoiceService.Dependencies(new VoiceStore(home.resolve("voice.json")), engine,
                clients, published, clock, null, AudioIngest.detached(), () -> clock.millis() * 1_000_000L));
    }
}
