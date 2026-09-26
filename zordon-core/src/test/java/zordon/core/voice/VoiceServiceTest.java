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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.voice.VoiceFakes.Clients;
import zordon.core.voice.VoiceFakes.Engine;
import zordon.core.voice.VoiceFakes.MutableClock;
import zordon.core.zwp.ZwpMethodException;

class VoiceServiceTest {

    @TempDir
    Path home;

    private final Engine engine = new Engine();
    private final MutableClock clock = new MutableClock();
    private final List<Map<String, Object>> published = new CopyOnWriteArrayList<>();

    @AcceptanceCriteria("SPEC-006/CA-1")
    @Test
    void oSnapshotTemOEstadoInteiroESemAudioNemTranscricao() {
        Map<String, Object> status = service(Clients.obedient()).status();

        // Campos sem valor são omitidos; nenhum outro campo existe.
        assertThat(List.of("mode", "effective", "reason", "activity", "openUntil", "capture", "host", "engine"))
                .containsAll(status.keySet())
                .containsAll(List.of("mode", "effective", "activity", "capture", "host", "engine"));
        assertThat(List.of("state", "requested", "confirmedAt")).containsAll(map(status, "capture").keySet());
        assertThat(List.of("connected", "device")).containsAll(map(status, "host").keySet());
        assertThat(map(status, "engine")).containsEntry("state", "absent").containsEntry("reason", AbsentVoiceEngine.REASON);
        assertThat(status).containsEntry("mode", "off").containsEntry("effective", "off");
    }

    @AcceptanceCriteria("SPEC-006/CA-2")
    @Test
    void semHostOModoEPersistidoEFicaIndisponivelComOMotivo() {
        Map<String, Object> status = service(Clients.obedient()).setMode(VoiceMode.WAKE);

        assertThat(status)
                .containsEntry("mode", "wake")
                .containsEntry("effective", "unavailable")
                .containsEntry("reason", "host do Windows não conectado");
        assertThat(service(Clients.obedient()).status()).containsEntry("mode", "wake");
    }

    @AcceptanceCriteria("SPEC-006/CA-3")
    @Test
    void comMotorAusenteOHostRecebeCapturaDesligadaEConfirma() {
        Clients clients = Clients.obedient();
        VoiceService voice = service(clients);
        voice.setMode(VoiceMode.WAKE);

        voice.hostConnected("h1");

        assertThat(clients.last("audio.setCaptureEnabled").params()).containsEntry("enabled", false);
        Map<String, Object> status = voice.status();
        assertThat(map(status, "capture"))
                .containsEntry("state", "off")
                .containsEntry("confirmedAt", clock.instant().toString());
        assertThat(status).containsEntry("effective", "unavailable").containsEntry("reason", AbsentVoiceEngine.REASON);
    }

    @AcceptanceCriteria("SPEC-006/CA-4")
    @Test
    void comHostEMotorProntosACapturaSoFicaLigadaDepoisDaConfirmacao() {
        Clients clients = new Clients();
        VoiceService voice = service(clients);
        engine.ready();
        voice.setMode(VoiceMode.WAKE);

        voice.hostConnected("h1");

        Clients.Call call = clients.last("audio.setCaptureEnabled");
        assertThat(call.params()).containsEntry("enabled", true);
        assertThat(map(voice.status(), "capture")).containsEntry("state", "pending").containsEntry("requested", true);

        call.answer().complete(Map.of("enabled", true));

        assertThat(map(voice.status(), "capture")).containsEntry("state", "on");
        assertThat(voice.status()).containsEntry("effective", "wake").doesNotContainKey("reason");
    }

    @AcceptanceCriteria("SPEC-006/CA-4")
    @Test
    void quandoOMotorFicaProntoACapturaEPedida() {
        Clients clients = Clients.obedient();
        VoiceService voice = service(clients);
        voice.setMode(VoiceMode.WAKE);
        voice.hostConnected("h1");

        engine.ready();

        assertThat(clients.last("audio.setCaptureEnabled").params()).containsEntry("enabled", true);
        assertThat(map(voice.status(), "capture")).containsEntry("state", "on");
    }

    @AcceptanceCriteria("SPEC-006/CA-5")
    @Test
    void desligarFicaPendenteAteOHostConfirmarENuncaAfirmaOffAntes() {
        Clients clients = Clients.obedient();
        VoiceService voice = captureOn(clients);
        clients.responder = null;

        voice.setMode(VoiceMode.OFF);

        assertThat(map(voice.status(), "capture")).containsEntry("state", "pending").containsEntry("requested", false);
        assertThat(published).noneMatch(snapshot -> "off".equals(map(snapshot, "capture").get("state")));

        clients.last("audio.setCaptureEnabled").answer().complete(Map.of("enabled", false));

        assertThat(map(voice.status(), "capture")).containsEntry("state", "off");
    }

    @AcceptanceCriteria("SPEC-006/CA-5")
    @Test
    void semConfirmacaoNoPrazoACapturaFicaDesconhecida() {
        Clients clients = Clients.obedient();
        VoiceService voice = captureOn(clients);
        clients.responder = null;

        voice.setMode(VoiceMode.OFF);
        clients.last("audio.setCaptureEnabled").answer().completeExceptionally(new ZwpMethodException(
                ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE, "audio.setCaptureEnabled sem resposta em 2000 ms"));

        assertThat(map(voice.status(), "capture"))
                .containsEntry("state", "unknown")
                .doesNotContainKey("confirmedAt");
    }

    @AcceptanceCriteria("SPEC-006/CA-5")
    @Test
    void respostaAtrasadaDeUmPedidoAntigoNaoMudaOEstado() {
        Clients clients = new Clients();
        VoiceService voice = service(clients);
        engine.ready();
        voice.hostConnected("h1");
        voice.setMode(VoiceMode.WAKE);
        Clients.Call ligar = clients.last("audio.setCaptureEnabled");

        voice.setMode(VoiceMode.OFF);
        ligar.answer().complete(Map.of("enabled", true));

        assertThat(map(voice.status(), "capture")).containsEntry("state", "pending").containsEntry("requested", false);
    }

    @AcceptanceCriteria("SPEC-006/CA-6")
    @Test
    void hostQueCaiDeixaCapturaDesconhecidaEAReconexaoReaplicaTudo() {
        Clients clients = Clients.obedient();
        VoiceService voice = service(clients);
        voice.setMode(VoiceMode.WAKE);
        voice.hostConnected("h1");
        voice.selectDevice("usb").join();

        voice.hostDisconnected("h1");

        Map<String, Object> status = voice.status();
        assertThat(map(status, "capture")).containsEntry("state", "unknown");
        assertThat(map(status, "host")).containsEntry("connected", false).doesNotContainKey("device");
        assertThat(status).containsEntry("effective", "unavailable").containsEntry("reason", VoiceService.NO_HOST);

        voice.hostConnected("h2");

        assertThat(clients.last("audio.setCaptureEnabled").session()).isEqualTo("h2");
        assertThat(clients.last("audio.selectDevice").session()).isEqualTo("h2");
        assertThat(clients.last("audio.selectDevice").params()).containsEntry("deviceId", "usb");
        assertThat(map(voice.status(), "host")).containsEntry("device", "Microfone usb");
    }

    @AcceptanceCriteria("SPEC-006/CA-7")
    @Test
    void openDuraCincoMinutosDoPedidoEVoltaAoModoAnteriorSemSerPersistido() {
        VoiceService voice = service(Clients.obedient());
        voice.setMode(VoiceMode.WAKE);

        Map<String, Object> open = voice.setMode(VoiceMode.OPEN);

        assertThat(open)
                .containsEntry("mode", "open")
                .containsEntry("openUntil", clock.instant().plus(Duration.ofMinutes(5)).toString());
        assertThat(new VoiceStore(store()).load().mode()).isEqualTo(VoiceMode.WAKE);

        clock.advance(Duration.ofMinutes(5));

        assertThat(voice.status()).containsEntry("mode", "wake").doesNotContainKey("openUntil");
    }

    @AcceptanceCriteria("SPEC-006/CA-8")
    @Test
    void semHostDispositivosFalhamComBridgeIndisponivel() {
        VoiceService voice = service(Clients.obedient());

        assertThatThrownBy(() -> voice.devices().join()).hasCauseInstanceOf(ZwpMethodException.class)
                .cause().satisfies(cause -> assertThat(((ZwpMethodException) cause).error().kind())
                        .isEqualTo(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE));
        assertThatThrownBy(() -> voice.selectDevice("usb").join()).hasCauseInstanceOf(ZwpMethodException.class);
    }

    @AcceptanceCriteria("SPEC-006/CA-8")
    @Test
    void escolherDispositivoPersisteAEscolha() {
        VoiceService voice = service(Clients.obedient());
        voice.hostConnected("h1");

        assertThat(voice.devices().join()).containsKey("devices");
        Map<String, Object> status = voice.selectDevice("usb").join();

        assertThat(map(status, "host")).containsEntry("device", "Microfone usb");
        assertThat(new VoiceStore(store()).load().deviceId()).isEqualTo("usb");
    }

    @Test
    void hostQueNaoObedeceDeixaAVozIndisponivelComOMotivo() {
        Clients clients = new Clients();
        clients.responder = call -> Map.of("enabled", false);
        VoiceService voice = service(clients);
        engine.ready();
        voice.setMode(VoiceMode.WAKE);

        voice.hostConnected("h1");

        assertThat(voice.status())
                .containsEntry("effective", "unavailable")
                .containsEntry("reason", "o host não ligou o microfone");
    }

    @AcceptanceCriteria("SPEC-007/CA-3")
    @Test
    void oMotivoDoHostApareceNoEstadoDaVoz() {
        Clients clients = new Clients();
        clients.responder = call -> Map.of(
                "enabled", false, "reason", "o Windows não liberou o microfone");
        VoiceService voice = service(clients);
        engine.ready();
        voice.setMode(VoiceMode.WAKE);

        voice.hostConnected("h1");

        assertThat(voice.status())
                .containsEntry("effective", "unavailable")
                .containsEntry("reason", "o host não ligou o microfone: o Windows não liberou o microfone");
    }

    @AcceptanceCriteria("SPEC-009/CA-3")
    @Test
    void cadaPedidoDeCapturaAnunciaUmStreamNovo() {
        Clients clients = Clients.obedient();
        VoiceService voice = service(clients);
        voice.hostConnected("h1");

        voice.testMicrophone(5);
        voice.setMode(VoiceMode.OFF);
        voice.testMicrophone(5);

        List<Object> streams = clients.of("audio.setCaptureEnabled").stream()
                .filter(call -> Boolean.TRUE.equals(call.params().get("enabled")))
                .map(call -> call.params().get("streamId"))
                .toList();
        assertThat(streams).hasSize(2).doesNotHaveDuplicates().doesNotContainNull();
    }

    @AcceptanceCriteria("SPEC-009/CA-7")
    @Test
    void testeLigaSemMotorPeloTempoPedidoEDesligaSozinho() {
        Clients clients = Clients.obedient();
        AudioIngest ingest = AudioIngest.detached();
        VoiceService voice = new VoiceService(new VoiceService.Dependencies(new VoiceStore(store()), engine, clients,
                published::add, clock, null, ingest, () -> clock.millis() * 1_000_000L));
        voice.hostConnected("h1");

        Map<String, Object> during = voice.testMicrophone(3);

        assertThat(clients.last("audio.setCaptureEnabled").params()).containsEntry("enabled", true);
        assertThat(map(during, "test")).containsEntry("until", clock.instant().plusSeconds(3).toString());
        assertThat(map(voice.status(), "capture")).containsEntry("state", "on");
        int stream = (int) clients.last("audio.setCaptureEnabled").params().get("streamId");
        for (int i = 0; i < 50; i++) {
            ingest.accept("h1", AudioIngestTest.audio(stream, i, AudioIngestTest.sine(0.1)));
        }

        clock.advance(Duration.ofSeconds(3));
        Map<String, Object> after = voice.status();

        assertThat(after).doesNotContainKey("test");
        assertThat(clients.last("audio.setCaptureEnabled").params()).containsEntry("enabled", false);
        assertThat(map(after, "lastTest"))
                .containsEntry("verdict", "ok")
                .containsEntry("frames", 50L)
                .containsKeys("peakDbfs", "averageDbfs");
    }

    @AcceptanceCriteria("SPEC-009/CA-7")
    @Test
    void testeSemHostOuForaDoIntervaloFalha() {
        VoiceService voice = service(Clients.obedient());

        assertThatThrownBy(() -> voice.testMicrophone(5)).isInstanceOf(ZwpMethodException.class)
                .satisfies(e -> assertThat(((ZwpMethodException) e).error().kind())
                        .isEqualTo(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE));
        voice.hostConnected("h1");
        assertThatThrownBy(() -> voice.testMicrophone(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> voice.testMicrophone(11)).isInstanceOf(IllegalArgumentException.class);
    }

    @AcceptanceCriteria("SPEC-009/CA-7")
    @Test
    void testeSemAudioOuComHostQueRecusaTerminaComoFalha() {
        Clients clients = Clients.obedient();
        VoiceService voice = service(clients);
        voice.hostConnected("h1");
        voice.testMicrophone(2);
        clock.advance(Duration.ofSeconds(2));

        assertThat(map(voice.status(), "lastTest"))
                .containsEntry("verdict", "failed")
                .containsEntry("message", "nenhum áudio chegou do host");

        clients.responder = call -> Map.of("enabled", false, "reason", "microfone em uso");
        voice.testMicrophone(5);

        assertThat(voice.status()).doesNotContainKey("test");
        assertThat(map(voice.status(), "lastTest")).containsEntry("verdict", "failed");
        assertThat(map(voice.status(), "lastTest").get("message").toString()).contains("microfone em uso");
    }

    @AcceptanceCriteria("SPEC-009/CA-7")
    @Test
    void desligarMicrofoneInterrompeOTeste() {
        Clients clients = Clients.obedient();
        VoiceService voice = service(clients);
        voice.hostConnected("h1");
        voice.testMicrophone(10);

        voice.setMode(VoiceMode.OFF);

        assertThat(voice.status()).doesNotContainKey("test");
        assertThat(clients.last("audio.setCaptureEnabled").params()).containsEntry("enabled", false);
        assertThat(map(voice.status(), "lastTest").get("message").toString()).contains("interrompido");
    }

    @AcceptanceCriteria("SPEC-009/CA-7")
    @Test
    void oTesteAcabaNoPrazoMesmoComORelogioDoWslSaltandoParaTras() throws Exception {
        // O relógio de parede do WSL salta a cada ~30 s, inclusive para trás (R7). Um
        // prazo medido nele deixou o microfone ligado até o usuário desligar à mão.
        java.util.concurrent.atomic.AtomicLong backwards = new java.util.concurrent.atomic.AtomicLong();
        java.time.Clock jumping = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return java.time.Instant.now().minusMillis(backwards.get()); }
        };
        Clients clients = Clients.obedient();
        java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor(Thread.ofVirtual().name("voice-deadlines").factory());
        try {
            VoiceService voice = new VoiceService(new VoiceService.Dependencies(new VoiceStore(store()), engine,
                    clients, published::add, jumping, scheduler, AudioIngest.detached(), System::nanoTime));
            voice.hostConnected("h1");

            voice.testMicrophone(1);
            backwards.set(3_000);

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (published.stream().noneMatch(snapshot -> snapshot.containsKey("lastTest"))) {
                assertThat(System.nanoTime()).as("o teste de 1 s não terminou em 3 s").isLessThan(deadline);
                Thread.sleep(20);
            }
            assertThat(clients.last("audio.setCaptureEnabled").params()).containsEntry("enabled", false);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void comDoisHostsOAnteriorLargaOMicrofone() {
        Clients clients = Clients.obedient();
        VoiceService voice = service(clients);
        voice.hostConnected("h1");

        voice.hostConnected("h2");

        assertThat(clients.of("audio.setCaptureEnabled"))
                .anySatisfy(call -> {
                    assertThat(call.session()).isEqualTo("h1");
                    assertThat(call.params()).containsEntry("enabled", false);
                });
        assertThat(clients.last("audio.setCaptureEnabled").session()).isEqualTo("h2");
    }

    @Test
    void arquivoCorrompidoDeixaAVozDesligada() throws Exception {
        java.nio.file.Files.createDirectories(store().getParent());
        java.nio.file.Files.writeString(store(), "{ isto não é json");

        assertThat(service(Clients.obedient()).status()).containsEntry("mode", "off");
    }

    @Test
    void cadaMudancaPublicaUmSnapshot() {
        VoiceService voice = service(Clients.obedient());

        voice.setMode(VoiceMode.WAKE);

        assertThat(published).isNotEmpty();
        assertThat(published.getLast()).containsEntry("mode", "wake");
    }

    private VoiceService captureOn(Clients clients) {
        VoiceService voice = service(clients);
        engine.ready();
        voice.setMode(VoiceMode.WAKE);
        voice.hostConnected("h1");
        assertThat(map(voice.status(), "capture")).containsEntry("state", "on");
        return voice;
    }

    private VoiceService service(Clients clients) {
        return new VoiceService(new VoiceStore(store()), engine, clients, published::add, clock, null);
    }

    private Path store() {
        return home.resolve("state").resolve("voice.json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> snapshot, String key) {
        return (Map<String, Object>) snapshot.get(key);
    }

}
