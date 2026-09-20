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
package zordon.desktop.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.trace.AcceptanceCriteria;

/** O que o cabeçalho e a tela de Voz dizem, a partir do snapshot do núcleo. */
class VoicePresentationTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    @AcceptanceCriteria("SPEC-006/CA-12")
    @Test
    void cadaEstadoTemExatamenteUmRotuloNoCabecalho() {
        assertThat(header(null)).isEqualTo("Voz indisponível");
        assertThat(header(snapshot("off", "off", "off", false, false))).isEqualTo("Voz desligada");
        assertThat(header(snapshot("wake", "unavailable", "off", null, true))).isEqualTo("Voz indisponível");
        assertThat(header(snapshot("wake", "wake", "on", true, true))).isEqualTo("Aguardando “Zordon”");
        assertThat(header(snapshot("push", "push", "on", true, true))).isEqualTo("Voz por atalho");

        Map<String, Object> listening = snapshot("wake", "wake", "on", true, true);
        listening.put("activity", "listening");
        assertThat(header(listening)).isEqualTo("Ouvindo comando");

        Map<String, Object> open = snapshot("open", "open", "on", true, true);
        open.put("openUntil", "2026-09-18T17:05:00Z");
        assertThat(header(open)).isEqualTo("Conversa aberta até 17:05");
    }

    @AcceptanceCriteria("SPEC-006/CA-12")
    @Test
    void desligarMicrofoneSoApareceComCapturaConfirmadaLigada() {
        assertThat(VoicePresentation.offersTurnOff(status(snapshot("wake", "wake", "on", true, true)))).isTrue();
        assertThat(VoicePresentation.offersTurnOff(status(snapshot("wake", "wake", "pending", true, true)))).isFalse();
        assertThat(VoicePresentation.offersTurnOff(status(snapshot("off", "off", "off", false, true)))).isFalse();
        assertThat(VoicePresentation.offersTurnOff(null)).isFalse();
    }

    @AcceptanceCriteria("SPEC-006/CA-14")
    @Test
    void semConfirmacaoNadaDizDesligado() {
        VoiceStatus turningOff = status(snapshot("off", "off", "pending", false, true));
        VoiceStatus turningOn = status(snapshot("wake", "wake", "pending", true, true));
        VoiceStatus unknown = status(snapshot("off", "off", "unknown", null, true));

        assertThat(VoicePresentation.headerLabel(turningOff, UTC)).isEqualTo("Desligando microfone…");
        assertThat(VoicePresentation.headerLabel(turningOn, UTC)).isEqualTo("Ligando microfone…");
        assertThat(VoicePresentation.headerLabel(unknown, UTC)).isEqualTo("Captura não confirmada");
        for (VoiceStatus voice : List.of(turningOff, turningOn, unknown)) {
            assertThat(VoicePresentation.headerLabel(voice, UTC).toLowerCase()).doesNotContain("desligad");
            assertThat(VoicePresentation.screen(voice, UTC).capture().toLowerCase()).doesNotContain("desligado");
        }
    }

    @AcceptanceCriteria("SPEC-006/CA-13")
    @Test
    @SuppressWarnings("unchecked")
    void aTelaSeparaPedidoDeEmVigorEMostraMotivoCapturaHostEDispositivo() {
        Map<String, Object> snapshot = snapshot("wake", "unavailable", "off", false, true);
        snapshot.put("reason", "motor de voz não instalado");
        ((Map<String, Object>) snapshot.get("capture")).put("confirmedAt", "2026-09-18T17:02:11Z");
        ((Map<String, Object>) snapshot.get("host")).put("device", "Microfone USB");

        VoicePresentation.Screen screen = VoicePresentation.screen(status(snapshot), UTC);

        assertThat(screen.desired()).isEqualTo("Aguardar “Zordon”");
        assertThat(screen.effective()).isEqualTo("Indisponível");
        assertThat(screen.reason()).isEqualTo("motor de voz não instalado");
        assertThat(screen.capture()).isEqualTo("Microfone desligado — confirmado pelo host às 17:02:11");
        assertThat(screen.host()).isEqualTo("Conectado");
        assertThat(screen.device()).isEqualTo("Microfone USB");
        assertThat(screen.engine()).isEqualTo("Não instalado");
        assertThat(VoicePresentation.testCard(status(snapshot)).enabled()).isTrue();
    }

    @AcceptanceCriteria("SPEC-006/CA-13")
    @Test
    void semHostATelaDizQueOZordonNaoControlaOMicrofone() {
        VoicePresentation.Screen screen = VoicePresentation.screen(
                status(snapshot("wake", "unavailable", "unknown", null, false)), UTC);

        assertThat(screen.capture()).contains("host do Windows não conectado");
        assertThat(screen.device()).isEqualTo("—");
    }

    @AcceptanceCriteria("SPEC-009/CA-8")
    @Test
    void duranteOTesteOCabecalhoDizTestandoMicrofone() {
        Map<String, Object> testing = snapshot("off", "off", "on", true, true);
        testing.put("test", Map.of("until", "2026-09-18T17:05:00Z"));

        assertThat(header(testing)).isEqualTo("Testando microfone…");
        assertThat(VoicePresentation.testCard(status(testing)).enabled()).isFalse();
        assertThat(VoicePresentation.testCard(status(testing)).hint()).contains("Fale");
    }

    @AcceptanceCriteria("SPEC-009/CA-8")
    @Test
    void semHostOBotaoFicaDesabilitadoComOMotivo() {
        VoicePresentation.TestCard card = VoicePresentation.testCard(status(snapshot("off", "off", "unknown", null, false)));

        assertThat(card.enabled()).isFalse();
        assertThat(card.hint()).contains("host do Windows");
        assertThat(VoicePresentation.testCard(null).enabled()).isFalse();
    }

    @AcceptanceCriteria("SPEC-009/CA-8")
    @Test
    void oVereditoAparecePassadoOTeste() {
        Map<String, Object> ok = snapshot("off", "off", "off", false, true);
        ok.put("lastTest", Map.of("verdict", "ok", "message", "sinal bom", "peakDbfs", -12.3, "averageDbfs", -30.1));
        Map<String, Object> failed = snapshot("off", "off", "off", false, true);
        failed.put("lastTest", Map.of("verdict", "failed", "message", "nenhum áudio chegou do host"));

        VoicePresentation.TestCard good = VoicePresentation.testCard(status(ok));
        assertThat(good.enabled()).isTrue();
        assertThat(good.resultOk()).isTrue();
        assertThat(good.result()).isEqualTo("✓  Sinal bom · pico -12,3 dBFS · média -30,1 dBFS");
        assertThat(VoicePresentation.testCard(status(failed)).result())
                .isEqualTo("✗  Não deu para testar: nenhum áudio chegou do host");
        assertThat(VoicePresentation.meter(-60)).isZero();
        assertThat(VoicePresentation.meter(-30)).isEqualTo(0.5);
        assertThat(VoicePresentation.meter(3)).isEqualTo(1.0);
    }

    @AcceptanceCriteria("SPEC-010/CA-3")
    @Test
    void aPilulaSoSomeEmRepousoEDizONucleoQuandoEleCai() {
        var online = zordon.zwp.CoreConnection.State.ONLINE;
        VoiceStatus resting = status(snapshot("off", "off", "off", false, true));
        VoiceStatus capturing = status(snapshot("wake", "wake", "on", true, true));
        VoiceStatus unconfirmed = status(snapshot("off", "off", "unknown", null, true));

        assertThat(VoicePresentation.atRest(online, resting, UTC)).isTrue();
        assertThat(VoicePresentation.atRest(online, capturing, UTC)).isFalse();
        assertThat(VoicePresentation.atRest(online, unconfirmed, UTC)).isFalse();
        assertThat(VoicePresentation.pillText(online, unconfirmed, UTC)).isEqualTo("Captura não confirmada");
        assertThat(VoicePresentation.atRest(zordon.zwp.CoreConnection.State.OFFLINE, resting, UTC)).isFalse();
        assertThat(VoicePresentation.pillText(zordon.zwp.CoreConnection.State.OFFLINE, resting, UTC))
                .isEqualTo("Núcleo offline — reconectando");
        assertThat(VoicePresentation.atRest(online, null, UTC)).isFalse();
    }

    @Test
    void oEstadoDoDesktopSegueOVoiceStateESomeOffline() {
        DesktopState state = new DesktopState();
        state.online("0.1.0");

        state.accept(new EventEnvelope(1, java.time.Instant.now(), EventType.VOICE_STATE,
                snapshot("push", "unavailable", "off", false, true)));
        assertThat(state.voiceProperty().get().mode()).isEqualTo("push");

        state.offline("conexão encerrada");
        assertThat(state.voiceProperty().get()).isNull();
    }

    @Test
    void transcricoesFicamNasUltimasVinte() {
        DesktopState state = new DesktopState();
        for (int i = 0; i < 25; i++) {
            state.accept(new EventEnvelope(i, java.time.Instant.now(), EventType.VOICE_STOPPED,
                    Map.of("text", "frase " + i, "confidence", 0.9)));
        }

        assertThat(state.transcriptions()).hasSize(DesktopState.TRANSCRIPTIONS_KEPT);
        assertThat(state.transcriptions().getFirst()).contains("frase 24");
    }

    private static String header(Map<String, Object> snapshot) {
        return VoicePresentation.headerLabel(snapshot == null ? null : status(snapshot), UTC);
    }

    private static VoiceStatus status(Map<String, Object> snapshot) {
        return VoiceStatus.from(snapshot);
    }

    /** Snapshot como o núcleo manda: campo sem valor é omitido. */
    private static Map<String, Object> snapshot(
            String mode, String effective, String capture, Boolean requested, boolean hostConnected) {
        Map<String, Object> captureState = new HashMap<>(Map.of("state", capture));
        if (requested != null) {
            captureState.put("requested", requested);
        }
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("mode", mode);
        snapshot.put("effective", effective);
        snapshot.put("activity", "idle");
        snapshot.put("capture", captureState);
        snapshot.put("host", new HashMap<>(Map.of("connected", hostConnected)));
        snapshot.put("engine", Map.of("state", "absent", "reason", "motor de voz não instalado"));
        return snapshot;
    }
}
