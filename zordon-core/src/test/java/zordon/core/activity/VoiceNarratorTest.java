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
package zordon.core.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

class VoiceNarratorTest {

    private static final Instant T0 = Instant.parse("2026-09-18T18:00:00Z");

    private final VoiceNarrator narrator = new VoiceNarrator();

    @AcceptanceCriteria("SPEC-012/CA-3")
    @Test
    void variasEtapasEmDoisSegundosViramUmaFalaADaUltima() {
        assertThat(narrator.offer(normal("Estou analisando o backend."), T0)).isEmpty();
        assertThat(narrator.offer(normal("Executando os testes."), at(500))).isEmpty();
        assertThat(narrator.offer(normal("Vou corrigir a configuração."), at(1500))).isEmpty();

        assertThat(narrator.tick(at(1900))).isEmpty();
        assertThat(narrator.tick(at(2000))).extracting(Narration::text).containsExactly("Vou corrigir a configuração.");
        assertThat(narrator.tick(at(3000))).isEmpty();
    }

    @AcceptanceCriteria("SPEC-012/CA-3")
    @Test
    void entreFalasNormaisHaQuatroSegundos() {
        narrator.offer(normal("Estou analisando o backend."), T0);
        assertThat(narrator.tick(at(2000))).hasSize(1);

        narrator.offer(normal("Executando os testes."), at(2100));
        assertThat(narrator.tick(at(4200))).isEmpty();
        assertThat(narrator.tick(at(6000))).extracting(Narration::text).containsExactly("Executando os testes.");
    }

    @AcceptanceCriteria("SPEC-012/CA-3")
    @Test
    void aMesmaFraseNaoSeRepeteEmTrintaSegundos() {
        Narration error = new Narration("Não consegui responder agora.", Narration.Priority.HIGH, "erro");

        assertThat(narrator.offer(error, T0)).hasSize(1);
        assertThat(narrator.offer(error, at(10_000))).isEmpty();
        assertThat(narrator.offer(error, at(30_001))).hasSize(1);
    }

    @AcceptanceCriteria("SPEC-012/CA-3")
    @Test
    void altaEAutorizacaoPassamNaFrenteEDescartamANormalPendente() {
        narrator.offer(normal("Executando os testes."), T0);

        Narration permission = new Narration("Preciso da sua autorização para continuar.",
                Narration.Priority.AUTHORIZATION, "autorizacao");
        assertThat(narrator.offer(permission, at(100))).containsExactly(permission);
        assertThat(narrator.tick(at(5000))).isEmpty();
    }

    private static Narration normal(String text) {
        return new Narration(text, Narration.Priority.NORMAL, "etapa");
    }

    private static Instant at(long millis) {
        return T0.plus(Duration.ofMillis(millis));
    }
}
