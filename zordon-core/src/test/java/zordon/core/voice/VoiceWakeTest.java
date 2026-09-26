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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;
import zordon.core.voice.VoiceFakes.Clients;
import zordon.core.voice.VoiceFakes.Engine;
import zordon.core.voice.VoiceFakes.MutableClock;

/** A palavra de ativação e a conversa sem clique, do lado do núcleo (SPEC-013). */
class VoiceWakeTest {

    @TempDir
    Path home;

    private final Engine engine = new Engine();
    private final Clients clients = Clients.obedient();
    private final AudioIngest ingest = AudioIngest.detached();
    private final AtomicLong ticker = new AtomicLong();
    private final MutableClock clock = new MutableClock();
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> transcripts = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> wakes = new CopyOnWriteArrayList<>();
    private final List<Boolean> attention = new CopyOnWriteArrayList<>();
    private VoiceService voice;

    @BeforeEach
    void setUp() {
        voice = new VoiceService(new VoiceService.Dependencies(new VoiceStore(home.resolve("voice.json")), engine,
                clients, snapshot -> { }, clock, null, ingest, ticker::get));
        voice.onCommand(commands::add);
        voice.onTranscript(transcripts::add);
        voice.onWakeOutcome(wakes::add);
        voice.onWake(attention::add);
    }

    /** Motor pronto com a palavra, host conectado e o modo pedido. */
    private void armed(VoiceMode mode) {
        engine.ready();
        voice.hostConnected("h1");
        voice.setMode(mode);
    }

    private static VoiceEngine.Transcript said(String text, double confidence) {
        return new VoiceEngine.Transcript(text, confidence, 1500, "end");
    }

    @AcceptanceCriteria("SPEC-013/CA-3")
    @Test
    void aPalavraAbreAEscutaEPedeOTom() {
        armed(VoiceMode.WAKE);
        assertThat(clients.last("audio.setCaptureEnabled").params()).containsEntry("enabled", true);
        assertThat(engine.calls).anyMatch(call -> call.endsWith(" wake") && call.startsWith("stream"));
        assertThat(voice.status()).containsEntry("effective", "wake").containsEntry("activity", "idle");

        engine.lastStream().wake(0.97);

        assertThat(attention).containsExactly(false);
        assertThat(voice.status()).containsEntry("activity", "listening");
    }

    @AcceptanceCriteria("SPEC-013/CA-4")
    @Test
    void oComandoDepoisDaPalavraViraTurno() {
        armed(VoiceMode.WAKE);
        VoiceEngine.Stream stream = engine.lastStream();

        stream.wake(0.95);
        stream.ended();
        assertThat(voice.status()).containsEntry("activity", "thinking");
        stream.transcript(said("Que horas são?", 0.93));

        assertThat(commands).containsExactly("Que horas são?");
        assertThat(wakes).containsExactly(Map.of("score", 0.95, "outcome", "command", "bargeIn", false));
        assertThat(voice.status()).containsEntry("activity", "idle");
        assertThat(engine.streams).hasSize(1);
    }

    @AcceptanceCriteria("SPEC-013/CA-5")
    @Test
    void aPalavraSemComandoVoltaADormirCalada() {
        armed(VoiceMode.WAKE);
        VoiceEngine.Stream stream = engine.lastStream();

        stream.wake(0.9);
        stream.ended();
        stream.transcript(new VoiceEngine.Transcript("", 0, 0, "silence"));

        assertThat(commands).isEmpty();
        assertThat(transcripts).singleElement().satisfies(event ->
                assertThat(event).containsEntry("outcome", "silence").doesNotContainKey("text"));
        assertThat(wakes).singleElement().satisfies(event -> assertThat(event).containsEntry("outcome", "silence"));
        assertThat(voice.status()).containsEntry("activity", "idle");
    }

    @AcceptanceCriteria("SPEC-013/CA-6")
    @Test
    void noModoAbertoCadaFalaViraComandoEEmCincoMinutosVoltaAWake() {
        armed(VoiceMode.WAKE);
        voice.setMode(VoiceMode.OPEN);
        long open = engine.lastStreamId();
        assertThat(engine.streamModes.get(open)).isEqualTo(VoiceEngine.StreamMode.OPEN);
        assertThat(voice.status()).containsEntry("effective", "open").containsKey("openUntil");

        engine.lastStream().transcript(said("Abre o navegador.", 0.9));
        engine.streams.get(open).transcript(said("E o terminal.", 0.9));
        assertThat(commands).containsExactly("Abre o navegador.", "E o terminal.");
        assertThat(wakes).isEmpty();

        ticker.addAndGet(VoiceService.OPEN_DURATION.toNanos());
        clock.advance(VoiceService.OPEN_DURATION);
        assertThat(voice.status()).containsEntry("effective", "wake");
        assertThat(engine.calls).contains("cancel " + open);
        assertThat(engine.streamModes.get(engine.lastStreamId())).isEqualTo(VoiceEngine.StreamMode.WAKE);
    }

    @AcceptanceCriteria("SPEC-013/CA-6")
    @Test
    void expirarModoAbertoEsperaAFalaEATranscricaoEmCurso() {
        armed(VoiceMode.WAKE);
        voice.setMode(VoiceMode.OPEN);
        long open = engine.lastStreamId();
        VoiceEngine.Stream listener = engine.lastStream();
        listener.speech();
        assertThat(voice.status()).containsEntry("activity", "listening");

        ticker.addAndGet(VoiceService.OPEN_DURATION.toNanos());
        voice.status();
        assertThat(engine.calls).doesNotContain("cancel " + open);
        listener.ended();
        assertThat(voice.status()).containsEntry("activity", "thinking");
        assertThat(engine.calls).doesNotContain("cancel " + open);
        listener.transcript(said("Termina este comando.", 0.9));

        assertThat(commands).containsExactly("Termina este comando.");
        assertThat(engine.calls).contains("cancel " + open);
        assertThat(voice.status()).containsEntry("effective", "wake");
    }

    @AcceptanceCriteria("SPEC-013/CA-7")
    @Test
    void pushFicaIndisponivelENaoLigaOMicrofone() {
        engine.ready();
        voice.hostConnected("h1");

        Map<String, Object> status = voice.setMode(VoiceMode.PUSH);

        assertThat(status).containsEntry("effective", "unavailable").containsEntry("reason", VoiceService.NO_PUSH);
        assertThat(clients.of("audio.setCaptureEnabled")).allSatisfy(call ->
                assertThat(call.params()).containsEntry("enabled", false));
        assertThat(engine.streams).isEmpty();
    }

    @AcceptanceCriteria("SPEC-013/CA-8")
    @Test
    void falandoAPalavraInterrompeEOuveDeNovo() {
        armed(VoiceMode.WAKE);
        long id = engine.lastStreamId();

        voice.speaking(true, true);
        assertThat(engine.calls).contains("duplex " + id + " true true");
        assertThat(voice.status()).containsEntry("activity", "speaking");

        engine.lastStream().wake(0.96);

        assertThat(attention).containsExactly(true);
        assertThat(voice.status()).containsEntry("activity", "listening");
        engine.lastStream().transcript(said("Para.", 0.9));
        assertThat(wakes).singleElement().satisfies(event -> assertThat(event).containsEntry("bargeIn", true));
    }

    @AcceptanceCriteria("SPEC-013/CA-9")
    @Test
    void falaComAPalavraDesarmaOFluxo() {
        armed(VoiceMode.WAKE);
        long id = engine.lastStreamId();

        voice.speaking(true, false);
        voice.speaking(false, true);

        assertThat(engine.calls).containsSubsequence("duplex " + id + " true false", "duplex " + id + " false true");
    }

    @AcceptanceCriteria("SPEC-013/CA-10")
    @Test
    void confiancaBaixaNaoAgeNemNoFluxoNemNoClique() {
        armed(VoiceMode.WAKE);
        VoiceEngine.Stream stream = engine.lastStream();
        stream.wake(0.9);
        stream.transcript(said("abre o arquivo secreto", 0.41));

        assertThat(commands).isEmpty();
        assertThat(transcripts.getLast()).containsEntry("outcome", "low_confidence");

        voice.setMode(VoiceMode.OFF);
        voice.startListening();
        long click = engine.listens.keySet().iterator().next();
        engine.listens.get(click).transcript(said("apaga tudo", 0.3));
        assertThat(commands).isEmpty();
        assertThat(transcripts.getLast()).containsEntry("outcome", "low_confidence");
    }

    @AcceptanceCriteria("SPEC-013/CA-11")
    @Test
    void falaDescartadaNaoDeixaTextoNosEventos() {
        armed(VoiceMode.WAKE);
        VoiceEngine.Stream stream = engine.lastStream();
        stream.wake(0.9);
        stream.transcript(said("conversa da sala", 0.5));

        assertThat(transcripts).allSatisfy(event -> assertThat(event).doesNotContainKey("text"));
        assertThat(wakes).allSatisfy(event -> assertThat(event).containsOnlyKeys("score", "outcome", "bargeIn"));
    }

    @AcceptanceCriteria("SPEC-013/CA-12")
    @Test
    void semOModeloDaPalavraWakeFicaIndisponivelEOCliqueFunciona() {
        engine.wakeWord = false;
        engine.ready();
        voice.hostConnected("h1");

        Map<String, Object> status = voice.setMode(VoiceMode.WAKE);

        assertThat(status).containsEntry("effective", "unavailable").containsEntry("reason", VoiceService.NO_WAKE_WORD);
        assertThat(engine.streams).isEmpty();
        assertThat(voice.startListening()).containsEntry("activity", "listening");
    }

    @Test
    void desligarFechaOFluxoEOMicrofone() {
        armed(VoiceMode.WAKE);
        long id = engine.lastStreamId();

        voice.setMode(VoiceMode.OFF);

        assertThat(engine.calls).contains("cancel " + id);
        assertThat(clients.last("audio.setCaptureEnabled").params()).containsEntry("enabled", false);
        assertThat(voice.status()).containsEntry("effective", "off");
    }

    @Test
    void cliqueComOFluxoAbertoAbreOComandoNele() {
        armed(VoiceMode.WAKE);
        long id = engine.lastStreamId();

        assertThat(voice.startListening()).containsEntry("activity", "listening");

        assertThat(engine.calls).contains("listenNow " + id);
        assertThat(engine.listens).isEmpty();
        engine.lastStream().transcript(said("Que horas são?", 0.9));
        assertThat(commands).containsExactly("Que horas são?");
        assertThat(wakes).isEmpty();
    }

    @Test
    void oMotorCaiEOFluxoReabreQuandoVoltar() {
        armed(VoiceMode.WAKE);
        long first = engine.lastStreamId();

        engine.lastStream().closed("lost");
        engine.streams.remove(first);
        engine.notReady();
        engine.ready();

        assertThat(engine.lastStreamId()).isGreaterThan(first);
        assertThat(Duration.ZERO).isZero();
    }
}
