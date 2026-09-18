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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

class ZordonConfigTest {

    @Test
    void emModoNatONucleoEscutaEmTodasAsInterfaces() {
        ZordonConfig config = ZordonConfig.fromEnvironment(Map.of("ZORDON_NETWORKING_MODE", "nat"));

        // localhostForwarding só alcança a VM se o serviço não estiver preso ao loopback.
        assertThat(config.bindAddress()).isEqualTo("0.0.0.0");
    }

    @Test
    void emModoEspelhadoONucleoNaoSeExpoeARedeLocal() {
        ZordonConfig config = ZordonConfig.fromEnvironment(Map.of("ZORDON_NETWORKING_MODE", "mirrored"));

        assertThat(config.bindAddress()).isEqualTo("127.0.0.1");
    }

    @AcceptanceCriteria("SPEC-002/CA-11")
    @Test
    void oPerfilDoWindowsNuncaEhDerivadoDoUsuarioDoWsl() {
        ZordonConfig semPerfil = ZordonConfig.fromEnvironment(Map.of("ZORDON_HOME", "/home/alguem/.zordon"));

        assertThat(semPerfil.windowsHome()).isEmpty();
        assertThat(semPerfil.windowsEndpointFile()).isEmpty();
    }

    @Test
    void oPerfilDoWindowsVemDaInstalacao() {
        ZordonConfig config = ZordonConfig.fromEnvironment(Map.of("ZORDON_WINDOWS_HOME", "/mnt/c/Users/outro"));

        assertThat(config.windowsEndpointFile())
                .hasValueSatisfying(path -> assertThat(path.toString())
                        .isEqualTo("/mnt/c/Users/outro/.zordon/endpoint.json"));
    }
}
