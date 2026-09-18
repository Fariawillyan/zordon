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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.HelloParams;
import zordon.api.zwp.HelloResult;
import zordon.api.zwp.ProtocolRange;
import zordon.api.zwp.ResumeRequest;
import zordon.api.zwp.ZwpCloseCode;
import zordon.api.zwp.ZwpProtocol;
import zordon.core.CoreUnderTest;
import zordon.zwp.ZwpClient;
import zordon.zwp.ZwpClientListener;
import zordon.zwp.ZwpRemoteException;
import zordon.api.trace.AcceptanceCriteria;

/** O handshake do ZWP, contra um núcleo de verdade. */
class ZwpHandshakeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ClientInfo DESKTOP = new ClientInfo(ClientKind.DESKTOP, "teste", "0.1.0");

    @TempDir
    Path home;

    @AcceptanceCriteria("SPEC-002/CA-1")
    @Test
    void helloRespondeComAVersaoDoProtocoloEOIdentificadorDaExecucao() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient client = client(core)) {
            HelloResult hello = client.connect(HelloParams.of(DESKTOP, List.of()), TIMEOUT);

            assertThat(hello.protocol()).isEqualTo(ZwpProtocol.VERSION);
            assertThat(hello.core().startId()).isEqualTo(core.endpoint().startId());
            assertThat(hello.resumed()).isFalse();
            assertThat(hello.sessionId()).isNotBlank();
        }
    }

    @AcceptanceCriteria("SPEC-002/CA-2")
    @Test
    void tokenInvalidoFechaAConexao() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home)) {
            AtomicInteger closeCode = new AtomicInteger();
            CountDownLatch closed = new CountDownLatch(1);

            try (ZwpClient client = new ZwpClient(URI.create(core.address()), "token-errado", new ZwpClientListener() {
                @Override
                public void onClosed(int code, String reason) {
                    closeCode.set(code);
                    closed.countDown();
                }
            })) {
                assertThatThrownBy(() -> client.connect(HelloParams.of(DESKTOP, List.of()), TIMEOUT))
                        .isInstanceOf(RuntimeException.class);
                assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(closeCode.get()).isEqualTo(ZwpCloseCode.UNAUTHORIZED);
            }
        }
    }

    @AcceptanceCriteria("SPEC-002/CA-3")
    @Test
    void handshakeComOriginEhRecusado() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home)) {
            BrowserLikeClient browser = new BrowserLikeClient(URI.create(core.address()), core.endpoint().token());

            browser.connectBlocking(5, TimeUnit.SECONDS);

            assertThat(browser.awaitClose()).isEqualTo(ZwpCloseCode.ORIGIN_PRESENT);
        }
    }

    @AcceptanceCriteria("SPEC-002/CA-4")
    @Test
    void metodoAntesDoHelloEhRecusado() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient client = client(core)) {
            client.open(TIMEOUT);

            assertThatThrownBy(() -> client.request("session.ping", Map.of()).join())
                    .hasCauseInstanceOf(ZwpRemoteException.class)
                    .hasMessageContaining("ERR_UNAUTHORIZED");
        }
    }

    @AcceptanceCriteria("SPEC-002/CA-5")
    @Test
    void retomadaComOutroStartIdNaoConcedeContinuidade() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient client = client(core)) {
            HelloResult hello = client.connect(
                    new HelloParams(
                            DESKTOP,
                            ProtocolRange.exactly(ZwpProtocol.VERSION),
                            List.of(),
                            new ResumeRequest("01OUTRONUCLEO0000000000000", 48_210)),
                    TIMEOUT);

            assertThat(hello.resumed()).isFalse();
        }
    }

    private ZwpClient client(CoreUnderTest core) {
        return new ZwpClient(URI.create(core.address()), core.endpoint().token(), new ZwpClientListener() {});
    }

    /** Um cliente que manda {@code Origin}, como todo navegador faz. */
    private static final class BrowserLikeClient extends org.java_websocket.client.WebSocketClient {

        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger code = new AtomicInteger();

        BrowserLikeClient(URI endpoint, String token) {
            super(
                    endpoint,
                    new org.java_websocket.drafts.Draft_6455(),
                    Map.of("Authorization", "Bearer " + token, "Origin", "https://exemplo.invalido"),
                    0);
        }

        int awaitClose() throws InterruptedException {
            closed.await(5, TimeUnit.SECONDS);
            return code.get();
        }

        @Override
        public void onOpen(org.java_websocket.handshake.ServerHandshake handshake) {}

        @Override
        public void onMessage(String message) {}

        @Override
        public void onClose(int closeCode, String reason, boolean remote) {
            code.set(closeCode);
            closed.countDown();
        }

        @Override
        public void onError(Exception e) {}
    }
}
