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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.voice.VoiceFakes.Clients;
import zordon.core.voice.VoiceFakes.Engine;
import zordon.core.voice.VoiceFakes.MutableClock;
import zordon.core.zwp.ZwpMethodException;

/** A escuta pedida por clique (SPEC-011): etapa 1, sem palavra de ativação. */
class VoiceListeningTest {

    @TempDir
    Path home;

    private final Engine engine = new Engine();
    private final Clients clients = Clients.obedient();
    private final AudioIngest ingest = AudioIngest.detached();
    private final AtomicLong ticker = new AtomicLong();
    private final List<Map<String, Object>> published = new CopyOnWriteArrayList<>();
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> transcripts = new CopyOnWriteArrayList<>();
    private VoiceService voice;

    @BeforeEach
    void setUp() {
        engine.wakeWord = false;
        voice = new VoiceService(new VoiceService.Dependencies(new VoiceStore(home.resolve("voice.json")), engine,
                clients, published::add, new MutableClock(), null, ingest, ticker::get));
        voice.onCommand(commands::add);
        voice.onTranscript(transcripts::add);
    }

    @AcceptanceCriteria("SPEC-011/CA-2")
    @Test
    void escutaLigaOMicrofoneRepassaOAudioEDesligaNoFimDaFala() {
        engine.ready();
        voice.hostConnected("h1");

        Map<String, Object> started = voice.startListening();

        Map<String, Object> on = clients.last("audio.setCaptureEnabled").params();
        assertThat(on).containsEntry("enabled", true).containsKey("streamId");
        assertThat(started).containsEntry("activity", "listening");
        ingest.accept("h1", AudioIngestTest.audio((int) on.get("streamId"), 0, AudioIngestTest.sine(0.1)));
        assertThat(engine.heard.size()).isEqualTo(640);

        engine.listens.get(1L).ended();

        assertThat(clients.last("audio.setCaptureEnabled").params()).containsEntry("enabled", false);
        assertThat(voice.status()).containsEntry("activity", "thinking");
    }

    @AcceptanceCriteria("SPEC-011/CA-2")
    @Test
    void pararEncerraNaHoraEOTetoDeDezesseisSegundosTambem() {
        engine.ready();
        voice.hostConnected("h1");
        voice.startListening();

        voice.stopListening();
        assertThat(engine.calls).contains("stop 1");

        engine.listens.get(1L).transcript(new VoiceEngine.Transcript("", 0, 0, "stopped"));
        voice.startListening();
        ticker.addAndGet(VoiceService.MAX_LISTENING.toNanos());
        voice.status();
        assertThat(engine.calls).contains("stop 2");
    }

    @AcceptanceCriteria("SPEC-011/CA-2")
    @Test
    void semHostOuSemMotorProntoAEscutaERecusada() {
        engine.ready();
        assertThatThrownBy(voice::startListening).isInstanceOf(ZwpMethodException.class)
                .satisfies(e -> assertThat(((ZwpMethodException) e).error().kind())
                        .isEqualTo(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE));

        voice.hostConnected("h1");
        engine.notReady();
        assertThatThrownBy(voice::startListening).isInstanceOf(ZwpMethodException.class)
                .satisfies(e -> assertThat(((ZwpMethodException) e).error().kind())
                        .isEqualTo(ZwpErrorKind.ERR_AI_UNAVAILABLE));
    }

    @AcceptanceCriteria("SPEC-011/CA-3")
    @Test
    void transcricaoViraComandoDeVozEVaziaNaoViraNada() {
        engine.ready();
        voice.hostConnected("h1");
        voice.startListening();
        engine.listens.get(1L).ended();

        engine.listens.get(1L).transcript(new VoiceEngine.Transcript("Que horas são.", 0.95, 1840, "end"));

        assertThat(commands).containsExactly("Que horas são.");
        assertThat(transcripts).singleElement().satisfies(t -> assertThat(t)
                .containsEntry("text", "Que horas são.").containsEntry("durationMs", 1840L));
        assertThat(voice.status()).containsEntry("activity", "idle");

        voice.startListening();
        engine.listens.get(2L).transcript(new VoiceEngine.Transcript("", 0, 0, "silence"));
        assertThat(commands).hasSize(1);
    }

    @AcceptanceCriteria("SPEC-011/CA-5")
    @Test
    void falaViraAtividadeSpeaking() {
        voice.speaking(true);
        assertThat(voice.status()).containsEntry("activity", "speaking");
        voice.speaking(false);
        assertThat(voice.status()).containsEntry("activity", "idle");
    }

    @Test
    void semPalavraDeAtivacaoOsModosNaoLigamOMicrofone() {
        engine.ready();
        voice.hostConnected("h1");

        Map<String, Object> status = voice.setMode(VoiceMode.WAKE);

        assertThat(status).containsEntry("effective", "unavailable").containsEntry("reason", VoiceService.NO_WAKE_WORD);
        assertThat(clients.of("audio.setCaptureEnabled"))
                .noneMatch(call -> Boolean.TRUE.equals(call.params().get("enabled")));
        assertThat(Duration.ofSeconds(16)).isEqualTo(VoiceService.MAX_LISTENING);
    }
}
