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

/**
 * Configuração do núcleo, vinda do ambiente.
 *
 * <p>Um arquivo de configuração chega no marco em que houver o que configurar. Até
 * lá, variáveis de ambiente definidas pela unit systemd bastam e não criam um
 * formato para manter.
 */
public record ZordonConfig(
        Path home, Optional<Path> windowsHome, String bindAddress, int port, NetworkingMode networkingMode) {

    /**
     * Modo de rede do WSL. Decide onde o núcleo escuta: em NAT, {@code localhost} do
     * Windows só alcança a VM se o serviço escutar em todas as interfaces; em modo
     * espelhado, escutar em {@code 0.0.0.0} exporia a porta à rede local.
     */
    public enum NetworkingMode {
        NAT("0.0.0.0"),
        MIRRORED("127.0.0.1");

        private final String defaultBind;

        NetworkingMode(String defaultBind) {
            this.defaultBind = defaultBind;
        }

        public String defaultBindAddress() {
            return defaultBind;
        }

        static NetworkingMode parse(String value) {
            return "mirrored".equalsIgnoreCase(value) ? MIRRORED : NAT;
        }
    }

    public static ZordonConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static ZordonConfig fromEnvironment(Map<String, String> environment) {
        Path home = Path.of(value(environment, "ZORDON_HOME")
                .orElseGet(() -> System.getProperty("user.home") + "/.zordon"));
        NetworkingMode mode = NetworkingMode.parse(value(environment, "ZORDON_NETWORKING_MODE").orElse("nat"));
        return new ZordonConfig(
                home,
                // Resolvido na instalação perguntando ao Windows, nunca derivado de
                // $USER: os dois usuários podem ter nomes diferentes (R21).
                value(environment, "ZORDON_WINDOWS_HOME").map(Path::of),
                value(environment, "ZORDON_BIND").orElseGet(mode::defaultBindAddress),
                value(environment, "ZORDON_PORT").map(Integer::parseInt).orElse(zordon.api.zwp.ZwpProtocol.DEFAULT_PORT),
                mode);
    }

    public Path endpointFile() {
        return home.resolve("endpoint.json");
    }

    public Optional<Path> windowsEndpointFile() {
        return windowsHome.map(path -> path.resolve(".zordon").resolve("endpoint.json"));
    }

    private static Optional<String> value(Map<String, String> environment, String key) {
        return Optional.ofNullable(environment.get(key)).filter(text -> !text.isBlank());
    }
}
