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
package zordon.zwp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

class ReconnectBackoffTest {

    @AcceptanceCriteria("SPEC-002/CA-10")
    @Test
    void cresceAteOTetoDeDezSegundos() {
        ReconnectBackoff backoff = new ReconnectBackoff();

        List<Duration> delays = IntStream.range(0, 10).mapToObj(i -> backoff.nextDelay()).toList();

        assertThat(delays.getFirst()).isBetween(Duration.ofMillis(200), Duration.ofMillis(300));
        assertThat(delays.getLast()).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(12));
        assertThat(delays).allSatisfy(delay -> assertThat(delay).isLessThanOrEqualTo(Duration.ofSeconds(12)));
    }

    @AcceptanceCriteria("SPEC-002/CA-10")
    @Test
    void doisClientesNaoReconectamNoMesmoInstante() {
        // Sem jitter, desktop e host voltam juntos depois de um reinício do núcleo.
        List<Duration> delays =
                IntStream.range(0, 20).mapToObj(i -> new ReconnectBackoff().nextDelay()).distinct().toList();

        assertThat(delays).hasSizeGreaterThan(1);
    }

    @Test
    void conexaoBemSucedidaZeraAEscalada() {
        ReconnectBackoff backoff = new ReconnectBackoff();
        IntStream.range(0, 5).forEach(i -> backoff.nextDelay());

        backoff.reset();

        assertThat(backoff.attempts()).isZero();
        assertThat(backoff.nextDelay()).isLessThan(Duration.ofMillis(400));
    }
}
