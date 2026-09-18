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
package zordon.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.TokenUsage;
import zordon.api.trace.AcceptanceCriteria;

class PricingTest {

    private final Pricing pricing = Pricing.defaults();

    @AcceptanceCriteria("SPEC-003/CA-3")
    @Test
    void calculaOCustoDeUmTurnoPelaTabelaDeReferencia() {
        // 1M de entrada e 1M de saída no Opus 5: US$ 5 + US$ 25.
        Money cost = pricing.costOf("claude-opus-5", new TokenUsage(1_000_000, 1_000_000, 0, 0));

        assertThat(cost.amount()).isEqualByComparingTo(new BigDecimal("30"));
    }

    @AcceptanceCriteria("SPEC-003/CA-3")
    @Test
    void lerDoCacheCustaUmDecimoDaEntrada() {
        Money fromCache = pricing.costOf("claude-opus-5", new TokenUsage(0, 0, 0, 1_000_000));

        assertThat(fromCache.amount()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @AcceptanceCriteria("SPEC-003/CA-3")
    @Test
    void modeloDesconhecidoCustaZeroEmVezDeUmPrecoInventado() {
        assertThat(pricing.knows("modelo-que-nao-existe")).isFalse();
        assertThat(pricing.costOf("modelo-que-nao-existe", new TokenUsage(100, 100, 0, 0)).amount())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void arquivoAusenteCaiNaTabelaPadrao() {
        assertThat(Pricing.load(java.nio.file.Path.of("/nao/existe/pricing.toml")).knows("claude-opus-5")).isTrue();
    }

    @AcceptanceCriteria("SPEC-003/CA-14")
    @Test
    void oArquivoDoUsuarioSubstituiOPrecoSemRecompilar(@TempDir Path home) throws Exception {
        Path file = home.resolve("pricing.toml");
        Files.writeString(file, """
                [models."claude-opus-5"]
                input = 4.00
                output = 20.00

                [models."modelo-novo"]
                input = 1
                output = 2
                cache_read = 0.05
                """);

        Pricing loaded = Pricing.load(file);

        assertThat(loaded.costOf("claude-opus-5", new TokenUsage(1_000_000, 0, 0, 0)).amount())
                .isEqualByComparingTo("4");
        assertThat(loaded.costOf("modelo-novo", new TokenUsage(0, 0, 0, 1_000_000)).amount())
                .isEqualByComparingTo("0.05");
        // O que o arquivo não menciona continua com o padrão.
        assertThat(loaded.knows("claude-haiku-4-5")).isTrue();
    }

    @AcceptanceCriteria("SPEC-003/CA-14")
    @Test
    void cacheOmitidoUsaAProporcaoPadraoDaEntrada(@TempDir Path home) throws Exception {
        Path file = home.resolve("pricing.toml");
        Files.writeString(file, "[models.x]\ninput = 10\noutput = 50\n");

        Money write = Pricing.load(file).costOf("x", new TokenUsage(0, 0, 1_000_000, 0));

        assertThat(write.amount()).isEqualByComparingTo("12.5");
    }

    @AcceptanceCriteria("SPEC-003/CA-14")
    @Test
    void arquivoInvalidoNaoDerrubaONucleo(@TempDir Path home) throws Exception {
        Path file = home.resolve("pricing.toml");
        Files.writeString(file, "[models.x]\ninput = \"caro\"\n");

        // Preço errado é melhor do que núcleo que não sobe; o log avisa.
        assertThat(Pricing.load(file).knows("claude-opus-5")).isTrue();
        assertThat(Pricing.load(file).knows("x")).isFalse();
    }
}
