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
package zordon.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.ZwpErrorKind;
import zordon.zwp.ZwpRemoteException;

class MicrophoneTest {

    private final FakeSound sound = new FakeSound();
    private final DiscardingSink sink = new DiscardingSink();
    private final Microphone microphone = new Microphone(sound, sink);
    private final AudioMethods audio = new AudioMethods(microphone);

    @AcceptanceCriteria("SPEC-007/CA-1")
    @Test
    void apresentaSeComoHostSoComCapturaDeAudio() {
        assertThat(ZordonHost.client().kind()).isEqualTo(ClientKind.HOST);
        // audio.playback entrou com a SPEC-011: o host também toca a fala do Zordon.
        assertThat(ZordonHost.CAPABILITIES)
                .containsExactly("audio.capture", "audio.playback", "windows.apps", "windows.notify");
        assertThat(JavaSoundSystem.FORMAT.getSampleRate()).isEqualTo(16_000f);
        assertThat(JavaSoundSystem.FORMAT.getChannels()).isEqualTo(1);
        assertThat(JavaSoundSystem.FORMAT.getSampleSizeInBits()).isEqualTo(16);
        assertThat(JavaSoundSystem.FORMAT.isBigEndian()).isFalse();
    }

    @AcceptanceCriteria("SPEC-007/CA-2")
    @Test
    void comecaDesligadoELigaSoAPedidoComALinhaAberta() {
        assertThat(microphone.capturing()).isFalse();
        assertThat(sound.opened).isEmpty();

        assertThat(audio.setCaptureEnabled(Map.of("enabled", true))).isEqualTo(Map.of("enabled", true));

        assertThat(microphone.capturing()).isTrue();
        assertThat(sound.last().device).isEqualTo(SoundSystem.DEFAULT);
        assertThat(sound.last().closed).isFalse();
    }

    @AcceptanceCriteria("SPEC-007/CA-2")
    @Test
    void desligarFechaALinhaAntesDeResponder() {
        audio.setCaptureEnabled(Map.of("enabled", true));
        FakeSound.Line line = sound.last();

        Map<String, Object> answer = audio.setCaptureEnabled(Map.of("enabled", false));

        assertThat(answer).isEqualTo(Map.of("enabled", false));
        assertThat(line.closed).isTrue();
        assertThat(microphone.capturing()).isFalse();
    }

    @AcceptanceCriteria("SPEC-007/CA-3")
    @Test
    void linhaQueNaoAbreRespondeDesligadoComOMotivo() {
        sound.failWith = "o Windows não liberou o microfone";

        Map<String, Object> answer = audio.setCaptureEnabled(Map.of("enabled", true));

        assertThat(answer)
                .containsEntry("enabled", false)
                .containsEntry("reason", "o Windows não liberou o microfone");
        assertThat(microphone.capturing()).isFalse();
    }

    @AcceptanceCriteria("SPEC-007/CA-4")
    @Test
    void perderONucleoDesligaEAReconexaoNaoReliga() {
        ZordonHost.Session session = new ZordonHost.Session(microphone);
        audio.setCaptureEnabled(Map.of("enabled", true));
        FakeSound.Line line = sound.last();

        session.onOffline("conexão encerrada");

        assertThat(line.closed).isTrue();
        assertThat(microphone.capturing()).isFalse();
        // Reconectar não liga nada: só um novo pedido do núcleo liga.
        assertThat(sound.opened).hasSize(1);
    }

    @AcceptanceCriteria("SPEC-007/CA-5")
    @Test
    @SuppressWarnings("unchecked")
    void listaOsDispositivosComOPadraoPrimeiro() {
        Map<String, Object> answer = audio.listDevices();

        assertThat(answer).containsEntry("selected", SoundSystem.DEFAULT);
        assertThat((List<Object>) answer.get("devices")).containsExactly(
                Map.<String, Object>of("id", "default", "name", "Padrão do Windows", "default", true),
                Map.<String, Object>of("id", "usb", "name", "Microfone USB", "default", false));
        assertThat(new JavaSoundSystem().devices().getFirst().id()).isEqualTo(SoundSystem.DEFAULT);
        assertThat(new JavaSoundSystem().devices()).noneMatch(device -> device.name().startsWith("Primary Sound Capture"));
    }

    @AcceptanceCriteria("SPEC-007/CA-5")
    @Test
    void dispositivoDesconhecidoFalhaComNaoEncontrado() {
        assertThatThrownBy(() -> audio.selectDevice(Map.of("deviceId", "fantasma")))
                .isInstanceOf(ZwpRemoteException.class)
                .satisfies(e -> assertThat(((ZwpRemoteException) e).kind()).contains(ZwpErrorKind.ERR_NOT_FOUND));
    }

    @AcceptanceCriteria("SPEC-007/CA-5")
    @Test
    void trocarDeDispositivoComACapturaLigadaReabreNoNovo() {
        audio.setCaptureEnabled(Map.of("enabled", true));
        FakeSound.Line before = sound.last();

        Map<String, Object> answer = audio.selectDevice(Map.of("deviceId", "usb"));

        assertThat(answer).isEqualTo(Map.of("selected", "usb", "name", "Microfone USB"));
        assertThat(before.closed).isTrue();
        assertThat(sound.last().device).isEqualTo("usb");
        assertThat(microphone.capturing()).isTrue();
    }

    @AcceptanceCriteria("SPEC-007/CA-6")
    @Test
    void framesDe640BytesVaoParaOSinkSemParar() throws Exception {
        microphone.enable();

        sound.last().deliver(5);

        await(() -> sink.frames() == 5);
        assertThat(sink.bytes()).isEqualTo(5L * Microphone.FRAME_BYTES);
        assertThat(microphone.capturing()).isTrue();
    }

    @AcceptanceCriteria("SPEC-007/CA-6")
    @Test
    void dispositivoQueParaDeEntregarDesligaAComOMotivo() throws Exception {
        microphone.enable();

        sound.last().end();

        await(() -> !microphone.capturing());
        assertThat(microphone.lastFailure()).contains(Microphone.STOPPED_DELIVERING);
        assertThat(audio.setCaptureEnabled(Map.of("enabled", false))).isEqualTo(Map.of("enabled", false));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condição não atingida em 5 s");
            }
            Thread.sleep(10);
        }
    }
}
