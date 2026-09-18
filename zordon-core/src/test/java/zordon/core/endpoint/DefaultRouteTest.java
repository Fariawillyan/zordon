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
package zordon.core.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

class DefaultRouteTest {

    /** Tabela real desta máquina: WSL em NAT, com Docker e duas redes de compose. */
    private static final List<String> WSL_WITH_DOCKER = List.of(
            "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT",
            "eth0\t00000000\t01D013AC\t0003\t0\t0\t0\t00000000\t0\t0\t0",
            "docker0\t000011AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0",
            "br-10fb45133474\t000012AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0",
            "br-5ebe16f6f999\t000014AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0");

    @AcceptanceCriteria("SPEC-002/CA-17")
    @Test
    void aInterfaceDaRotaPadraoEhAQueOWindowsAlcanca() {
        assertThat(DefaultRoute.interfaceName(WSL_WITH_DOCKER)).hasValue("eth0");
    }

    @AcceptanceCriteria("SPEC-002/CA-17")
    @Test
    void semRotaPadraoNaoHaPalpite() {
        assertThat(DefaultRoute.interfaceName(List.of(WSL_WITH_DOCKER.getFirst(), WSL_WITH_DOCKER.get(2))))
                .isEmpty();
    }

    @Test
    void tabelaVaziaNaoQuebra() {
        assertThat(DefaultRoute.interfaceName(List.of())).isEmpty();
    }
}
