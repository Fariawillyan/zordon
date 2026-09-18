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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.HelloResult;
import zordon.core.CoreUnderTest;
import zordon.zwp.CoreConnection;

/**
 * O que acontece quando o núcleo cai e volta.
 *
 * <p>É o teste que prova a promessa do marco M0/M1: o usuário não reinicia nada, e
 * a interface não mostra estado velho como se fosse atual.
 */
class ReconnectionTest {

    private static final ClientInfo DESKTOP = new ClientInfo(ClientKind.DESKTOP, "teste", "0.1.0");

    @TempDir
    Path home;

    @AcceptanceCriteria("SPEC-002/CA-16")
    @Test
    void oClienteVoltaSozinhoQuandoONucleoReinicia() throws Exception {
        List<String> transitions = new CopyOnWriteArrayList<>();
        AtomicBoolean continuity = new AtomicBoolean(true);

        CoreUnderTest first = CoreUnderTest.start(home);
        // Lidos agora: o endpoint.json do primeiro núcleo é sobrescrito pelo segundo.
        String firstStartId = first.endpoint().startId();
        String firstToken = first.endpoint().token();

        try (CoreConnection connection = new CoreConnection(
                home.resolve("endpoint.json"), DESKTOP, List.of(), new CoreConnection.Listener() {
                    @Override
                    public void onOnline(HelloResult hello, boolean resumed) {
                        continuity.set(resumed);
                        transitions.add("ONLINE:" + hello.core().startId());
                    }

                    @Override
                    public void onOffline(String reason) {
                        transitions.add("OFFLINE");
                    }
                })) {
            connection.start();
            await(() -> connection.state() == CoreConnection.State.ONLINE);

            first.close();
            await(() -> connection.state() == CoreConnection.State.OFFLINE);

            // O núcleo volta — outra execução, outro startId, outro token.
            try (CoreUnderTest second = CoreUnderTest.start(home)) {
                await(() -> connection.state() == CoreConnection.State.ONLINE);

                assertThat(second.endpoint().startId()).isNotEqualTo(firstStartId);
                assertThat(second.endpoint().token()).isNotEqualTo(firstToken);
                // Sem continuidade: o cliente descarta o estado volátil em vez de
                // mostrar um container que não existe mais (ADR-0011).
                assertThat(continuity).isFalse();
                assertThat(transitions).containsSubsequence(
                        "ONLINE:" + firstStartId, "OFFLINE", "ONLINE:" + second.endpoint().startId());
            }
        }
    }

    @AcceptanceCriteria("SPEC-002/CA-16")
    @Test
    void semNucleoNenhumOClienteEsperaEmVezDeFalhar() throws Exception {
        try (CoreConnection connection = new CoreConnection(
                home.resolve("endpoint.json"), DESKTOP, List.of(), new CoreConnection.Listener() {})) {
            connection.start();

            // Nenhum endpoint.json existe ainda: o cliente fica offline observando,
            // e não desiste nem lança.
            TimeUnit.MILLISECONDS.sleep(500);
            assertThat(connection.state()).isEqualTo(CoreConnection.State.OFFLINE);

            try (CoreUnderTest core = CoreUnderTest.start(home)) {
                await(() -> connection.state() == CoreConnection.State.ONLINE);
                assertThat(connection.state()).isEqualTo(CoreConnection.State.ONLINE);
            }
        }
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("condição não ocorreu em 20 s");
    }
}
