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

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.HelloResult;
import zordon.zwp.CoreConnection;

/**
 * O host do Windows: sem janela, iniciado no logon, controla o microfone a pedido
 * do núcleo e diz o que fez (SPEC-007). Não decide nada.
 */
@Spec("SPEC-007")
public final class ZordonHost {

    static final String VERSION = "0.1.0";

    /** O que o host sabe fazer. Cresce com cada entrega, e nunca antes de ser verdade. */
    static final List<String> CAPABILITIES = List.of("audio.capture", "audio.playback", "windows.apps", "windows.notify");

    private static final Logger log = LoggerFactory.getLogger(ZordonHost.class);

    private ZordonHost() {}

    static ClientInfo client() {
        return new ClientInfo(ClientKind.HOST, "zordon-host", VERSION);
    }

    public static void main(String[] args) throws InterruptedException {
        java.util.concurrent.atomic.AtomicReference<CoreConnection> link = new java.util.concurrent.atomic.AtomicReference<>();
        StreamingSink stream = new StreamingSink(frame -> link.get() != null && link.get().sendBinary(frame));
        JavaSoundSystem sound = new JavaSoundSystem();
        Microphone microphone = new Microphone(sound, stream);
        Speaker speaker = new Speaker(sound);
        CoreConnection connection = new CoreConnection(
                HostPaths.endpointFile(), client(), CAPABILITIES, new Session(microphone, stream, speaker));
        link.set(connection);
        new AudioMethods(microphone, stream).speaker(speaker).registerOn(connection);
        WindowsApps.system().registerOn(connection);
        WindowsNotifications notifications = WindowsNotifications.system();
        notifications.registerOn(connection);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            notifications.close();
            microphone.close();
            speaker.close();
            connection.close();
        }, "zordon-host-shutdown"));

        log.info("zordon-host {} procurando o núcleo em {}", VERSION, HostPaths.endpointFile());
        connection.start();
        Thread.currentThread().join();
    }

    /**
     * A captura não sobrevive à conexão (SPEC-007 §5, invariante 2): sem núcleo,
     * não há quem mande desligar.
     */
    static final class Session implements CoreConnection.Listener {

        private final Microphone microphone;
        private final StreamingSink stream;
        private final Speaker speaker;

        Session(Microphone microphone) {
            this(microphone, null, null);
        }

        Session(Microphone microphone, StreamingSink stream) {
            this(microphone, stream, null);
        }

        Session(Microphone microphone, StreamingSink stream, Speaker speaker) {
            this.microphone = microphone;
            this.stream = stream;
            this.speaker = speaker;
        }

        @Override
        public void onBinary(zordon.api.zwp.BinaryFrame frame) {
            if (speaker != null) {
                speaker.frame(frame);
            }
        }

        @Override
        public void onOnline(HelloResult hello, boolean resumed) {
            if (stream != null) {
                stream.creditLimit(hello.audioCreditFrames());
            }
            log.info("conectado ao núcleo {}", hello.core().version());
        }

        @Override
        public void onOffline(String reason) {
            microphone.disable();
            if (stream != null) {
                stream.abort();
            }
            if (speaker != null) {
                speaker.close();
            }
            log.info("sem núcleo ({}); microfone desligado", reason);
        }
    }
}
