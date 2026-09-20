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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.trace.AcceptanceCriteria;
import zordon.api.zwp.ClientInfo;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.HelloParams;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.CoreUnderTest;
import zordon.zwp.ZwpClient;
import zordon.zwp.ZwpClientListener;
import zordon.zwp.ZwpRemoteException;

/** {@code voice.*} com o núcleo inteiro, um host e um desktop de teste. */
class VoiceMethodsTest {

    @TempDir
    Path home;

    @AcceptanceCriteria("SPEC-006/CA-9")
    @Test
    @SuppressWarnings("unchecked")
    void soOHostControlaOMicrofoneMesmoQueODesktopDeclareACapacidade() throws Exception {
        List<Map<String, Object>> askedHost = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> askedDesktop = new CopyOnWriteArrayList<>();
        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient desktop = client(core, new ZwpClientListener() {});
                ZwpClient host = client(core, new ZwpClientListener() {})) {
            desktop.handle("audio.setCaptureEnabled", params -> {
                askedDesktop.add(params);
                return Map.of("enabled", params.get("enabled"));
            });
            host.handle("audio.setCaptureEnabled", params -> {
                askedHost.add(params);
                return Map.of("enabled", params.get("enabled"));
            });
            hello(desktop, ClientKind.DESKTOP);
            hello(host, ClientKind.HOST);

            desktop.request("voice.setMode", Map.of("mode", "wake")).get(5, TimeUnit.SECONDS);
            await(() -> !askedHost.isEmpty()
                    && "off".equals(((Map<String, Object>) status(desktop).get("capture")).get("state")));

            assertThat(askedHost).allSatisfy(params -> assertThat(params).containsEntry("enabled", false));
            assertThat(askedDesktop).isEmpty();
            assertThat(status(desktop)).containsEntry("mode", "wake").containsEntry("effective", "unavailable");
        }
    }

    @AcceptanceCriteria("SPEC-006/CA-8")
    @Test
    void dispositivosPassamPeloHostESemEleFalham() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient desktop = client(core, new ZwpClientListener() {})) {
            hello(desktop, ClientKind.DESKTOP);

            Throwable withoutHost = catchThrowable(() -> desktop.request("voice.devices", Map.of()).get(5, TimeUnit.SECONDS));
            assertThat(withoutHost).hasCauseInstanceOf(ZwpRemoteException.class);
            assertThat(((ZwpRemoteException) withoutHost.getCause()).kind()).contains(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE);

            try (ZwpClient host = client(core, new ZwpClientListener() {})) {
                host.handle("audio.setCaptureEnabled", params -> Map.of("enabled", params.get("enabled")));
                host.handle("audio.listDevices", params -> Map.of(
                        "devices", List.of(Map.of("id", "usb", "name", "Microfone USB", "default", true)),
                        "selected", "usb"));
                hello(host, ClientKind.HOST);

                Map<String, Object> devices = desktop.request("voice.devices", Map.of()).get(5, TimeUnit.SECONDS);

                assertThat(devices).containsEntry("selected", "usb");
                assertThat((List<?>) devices.get("devices")).hasSize(1);
            }
        }
    }

    @AcceptanceCriteria("SPEC-006/CA-1")
    @Test
    void mudancaChegaComoVoiceStateEODiagnosticoMostraAVoz() throws Exception {
        List<EventEnvelope> events = new CopyOnWriteArrayList<>();
        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient desktop = client(core, new ZwpClientListener() {
                    @Override
                    public void onEvent(EventEnvelope event) {
                        events.add(event);
                    }
                })) {
            hello(desktop, ClientKind.DESKTOP);
            desktop.request("session.subscribe", Map.of("topics", List.of("voice"))).get(5, TimeUnit.SECONDS);

            desktop.request("voice.setMode", Map.of("mode", "push")).get(5, TimeUnit.SECONDS);
            await(() -> events.stream().anyMatch(event -> event.type() == EventType.VOICE_STATE
                    && "push".equals(event.payload().get("mode"))));

            Map<String, Object> diagnostics = desktop.request("system.diagnostics", Map.of()).get(5, TimeUnit.SECONDS);
            assertThat(diagnostics).containsKey("voice");
            assertThat(diagnostics.get("voice").toString()).contains("push");
        }
    }

    @Test
    void modoInvalidoEArgumentoInvalido() throws Exception {
        try (CoreUnderTest core = CoreUnderTest.start(home);
                ZwpClient desktop = client(core, new ZwpClientListener() {})) {
            hello(desktop, ClientKind.DESKTOP);

            Throwable thrown = catchThrowable(() ->
                    desktop.request("voice.setMode", Map.of("mode", "sempre")).get(5, TimeUnit.SECONDS));

            assertThat(((ZwpRemoteException) thrown.getCause()).kind()).contains(ZwpErrorKind.ERR_INVALID_ARGUMENT);
        }
    }

    private static Map<String, Object> status(ZwpClient client) {
        return client.request("voice.status", Map.of()).orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static ZwpClient client(CoreUnderTest core, ZwpClientListener listener) {
        return new ZwpClient(URI.create(core.address()), core.endpoint().token(), listener);
    }

    private static void hello(ZwpClient client, ClientKind kind) throws Exception {
        client.connect(HelloParams.of(new ClientInfo(kind, "teste", "0.0.0"), List.of("audio.capture")),
                Duration.ofSeconds(10));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condição não foi atingida em 5 s");
            }
            Thread.sleep(20);
        }
    }
}
