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
package zordon.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import zordon.api.zwp.EndpointFile;
import zordon.core.platform.SystemdNotifier;
import zordon.zwp.EndpointFileStore;

/**
 * Um núcleo real, numa porta efêmera, com o {@code ZORDON_HOME} num diretório
 * temporário.
 *
 * <p>É um fake de ambiente, não de comportamento: o que roda no teste é o mesmo
 * servidor ZWP que roda em produção (docs/testing/strategy.md §7).
 */
public final class CoreUnderTest implements AutoCloseable {

    private final ZordonCore core;
    private final ZordonConfig config;

    private CoreUnderTest(ZordonCore core, ZordonConfig config) {
        this.core = core;
        this.config = config;
    }

    public static CoreUnderTest start(Path home) throws InterruptedException {
        return start(home, Map.of());
    }

    /** Com um ambiente explícito — é assim que um teste dá uma chave ao núcleo sem usar a real. */
    public static CoreUnderTest start(Path home, Map<String, String> environment) throws InterruptedException {
        ZordonConfig config = new ZordonConfig(
                home, Optional.empty(), "127.0.0.1", 0, ZordonConfig.NetworkingMode.MIRRORED);
        // Nunca o motor de voz real desta máquina: um socket que não existe, salvo pedido.
        Map<String, String> isolated = new java.util.HashMap<>(environment);
        isolated.putIfAbsent("ZORDON_VOICE_SOCKET", home.resolve("voice.sock").toString());
        ZordonCore core = new ZordonCore(config, new SystemdNotifier(), isolated);
        core.start();
        return new CoreUnderTest(core, config);
    }

    public EndpointFile endpoint() {
        return new EndpointFileStore()
                .read(config.endpointFile())
                .orElseThrow(() -> new IllegalStateException("núcleo não publicou o endpoint"));
    }

    public String address() {
        return endpoint().endpoints().getFirst();
    }

    public ZordonCore core() {
        return core;
    }

    public void publish(zordon.api.event.EventType type, Map<String, Object> payload) {
        core.events().publish(type, payload);
    }

    @Override
    public void close() {
        core.close();
    }
}
