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

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

class StartIdTest {

    @Test
    void temFormatoUlidECaracteresDoAlfabetoCrockford() {
        assertThat(StartId.generate()).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}");
    }

    @AcceptanceCriteria("SPEC-002/CA-15")
    @Test
    void ordenaLexicograficamentePorTempo() {
        // É o que permite ordenar execuções do núcleo sem consultar relógio nenhum:
        // um startId maior é sempre de uma inicialização posterior.
        java.security.SecureRandom random = new java.security.SecureRandom();
        String antes = StartId.generate(java.time.Instant.parse("2026-09-17T22:00:00Z"), random);
        String depois = StartId.generate(java.time.Instant.parse("2026-09-17T22:00:01Z"), random);

        assertThat(antes).isLessThan(depois);
    }

    @AcceptanceCriteria("SPEC-002/CA-15")
    @Test
    void carimbaOTempoNosPrimeirosDezCaracteres() {
        String id = StartId.generate(
                java.time.Instant.parse("2026-09-17T22:31:04Z"), new java.security.SecureRandom());

        assertThat(id).startsWith("01M");
    }

    @Test
    void naoSeRepeteEntreInicializacoes() {
        assertThat(IntStream.range(0, 200).mapToObj(i -> StartId.generate()).distinct().count())
                .isEqualTo(200);
    }
}
