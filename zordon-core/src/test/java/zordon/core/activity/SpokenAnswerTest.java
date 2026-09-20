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

import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

/** A resposta do modelo virando algo que se ouve (SPEC-034). */
class SpokenAnswerTest {

    @AcceptanceCriteria("SPEC-034/CA-1")
    @Test
    void aMarcacaoNaoEhFalada() {
        String resposta = """
                ## Containers

                Estão rodando **três** containers: `postgres`, `redis` e *nginx*.
                Veja o [painel](http://localhost:9000) para detalhes.

                - postgres: saudável
                - redis: saudável
                """;

        String falado = SpokenAnswer.of(resposta);

        assertThat(falado)
                .doesNotContain("*").doesNotContain("#").doesNotContain("`")
                .doesNotContain("[").doesNotContain("](")
                .contains("Estão rodando três containers")
                .contains("postgres, redis e nginx")
                .contains("painel");
        assertThat(falado).doesNotContain("http");
    }

    @AcceptanceCriteria("SPEC-034/CA-2")
    @Test
    void respostaLongaViraResumoQueApontaParaATela() {
        String frase = "O núcleo está no ar e respondendo normalmente. ";
        String resposta = frase.repeat(20);

        String falado = SpokenAnswer.of(resposta);

        assertThat(falado.length()).isLessThan(resposta.length());
        assertThat(falado).endsWith(SpokenAnswer.ON_SCREEN);
        // Corta no fim de uma frase: cortar no meio soa como defeito do motor.
        assertThat(falado.replace(" " + SpokenAnswer.ON_SCREEN, "")).endsWith(".");
        assertThat(falado.length()).isLessThanOrEqualTo(SpokenAnswer.MAX_CHARS + SpokenAnswer.ON_SCREEN.length() + 2);
    }

    @AcceptanceCriteria("SPEC-034/CA-3")
    @Test
    void blocoDeCodigoNaoEhDitadoEAVozDizOndeEleEsta() {
        String resposta = """
                Para reiniciar, rode:

                ```bash
                sudo systemctl restart zordon
                ```
                """;

        String falado = SpokenAnswer.of(resposta);

        assertThat(falado).contains("Para reiniciar, rode")
                .contains(SpokenAnswer.CODE_ON_SCREEN)
                .doesNotContain("systemctl")
                .doesNotContain("```");
    }

    @AcceptanceCriteria("SPEC-034/CA-2")
    @Test
    void respostaCurtaEhFaladaInteira() {
        String falado = SpokenAnswer.of("São 15h40.");

        assertThat(falado).isEqualTo("São 15h40.");
        assertThat(falado).doesNotContain(SpokenAnswer.ON_SCREEN);
    }

    @Test
    void respostaVaziaOuSoMarcacaoNaoViraFala() {
        assertThat(SpokenAnswer.of("")).isEmpty();
        assertThat(SpokenAnswer.of(null)).isEmpty();
        assertThat(SpokenAnswer.of("---")).isEmpty();
    }

    @Test
    void tabelaNaoEhLidaCelulaPorCelula() {
        String resposta = """
                Resumo do uso:

                | Dia | Tokens |
                |---|---|
                | Hoje | 1200 |
                """;

        assertThat(SpokenAnswer.of(resposta)).startsWith("Resumo do uso:").doesNotContain("|");
    }
}
