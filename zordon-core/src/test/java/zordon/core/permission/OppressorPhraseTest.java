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
package zordon.core.permission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import zordon.api.trace.AcceptanceCriteria;

/** O que a voz pede sobre o OPPRESSOR MODE (SPEC-036 CA-9). */
class OppressorPhraseTest {

    @AcceptanceCriteria("SPEC-036/CA-9")
    @ParameterizedTest
    @ValueSource(strings = {"modo opressor", "Zordon, ativar o modo opressor",
        "MODO OPRESSOR", "entra no modo oppressor", "oppressor mode"})
    void pedirOModoAbreOPedidoDeSenha(String falado) {
        assertThat(OppressorPhrase.of(falado)).isEqualTo(OppressorPhrase.ENTER);
    }

    @AcceptanceCriteria("SPEC-036/CA-9")
    @ParameterizedTest
    @ValueSource(strings = {"sair do modo opressor", "desliga o modo opressor",
        "Zordon, encerrar modo opressor", "desativa o modo oppressor", "cancelar o modo opressor"})
    void pedirParaSairDesligaSemSenha(String falado) {
        assertThat(OppressorPhrase.of(falado)).isEqualTo(OppressorPhrase.EXIT);
    }

    @AcceptanceCriteria("SPEC-036/CA-9")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"que horas são", "abre o navegador", "   ",
        "me fala sobre modos de operação", "opressor"})
    void qualquerOutraFalaSegueComoTurno(String falado) {
        assertThat(OppressorPhrase.of(falado)).isEqualTo(OppressorPhrase.NONE);
    }

    @Test
    void acentoEPontuacaoNaoAtrapalham() {
        // A transcrição vem com pontuação e caixa variadas; a leitura não pode
        // depender disso para decidir sobre o motor de permissão.
        assertThat(OppressorPhrase.of("Zordon! Módo Opressor?")).isEqualTo(OppressorPhrase.ENTER);
    }

    @Test
    void verboDentroDeOutraPalavraNaoContaComoSair() {
        // "disparar" contém "para": sem casar palavra inteira, entrar viraria sair.
        assertThat(OppressorPhrase.of("modo opressor para disparar tudo")).isEqualTo(OppressorPhrase.EXIT);
        assertThat(OppressorPhrase.of("modo opressor disparando tudo")).isEqualTo(OppressorPhrase.ENTER);
    }
}
