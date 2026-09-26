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
package zordon.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import zordon.api.trace.AcceptanceCriteria;

/** A senha mestre do OPPRESSOR MODE (SPEC-036 §10). */
class MasterPasswordTest {

    private static final String SENHA = "abreteSesamo2026";

    @AcceptanceCriteria("SPEC-036/CA-1")
    @Test
    void aSenhaCorretaConfereEAErradaNao() {
        String stored = MasterPassword.derive(SENHA.toCharArray());
        assertThat(MasterPassword.matches(SENHA.toCharArray(), stored)).isTrue();
        assertThat(MasterPassword.matches("abreteSesamo2025".toCharArray(), stored)).isFalse();
        assertThat(MasterPassword.matches("".toCharArray(), stored)).isFalse();
    }

    @AcceptanceCriteria("SPEC-036/CA-7")
    @Test
    void oGuardadoNaoContemASenha() {
        String stored = MasterPassword.derive(SENHA.toCharArray());
        assertThat(stored).doesNotContain(SENHA).startsWith("pbkdf2$" + MasterPassword.ITERATIONS + "$");
        // Sal por senha: a mesma senha guardada duas vezes não dá o mesmo texto,
        // senão um hash igual ao do vizinho entregaria que a senha é igual.
        assertThat(stored).isNotEqualTo(MasterPassword.derive(SENHA.toCharArray()));
    }

    @AcceptanceCriteria("SPEC-036/CA-2")
    @ParameterizedTest
    @ValueSource(strings = {"", "pbkdf2", "pbkdf2$1$2", "qualquer$600000$c2Fs$Y2hhdmU", "pbkdf2$x$c2Fs$Y2hhdmU",
        "pbkdf2$600000$não-base64!$Y2hhdmU"})
    void guardadoMalformadoNuncaConfere(String stored) {
        // Sem senha utilizável o modo não abre: corrompido é "não confere",
        // nunca "confere" nem exceção que suba para o chamador.
        assertThat(MasterPassword.matches(SENHA.toCharArray(), stored)).isFalse();
    }

    @Test
    void nulosNaoConferem() {
        assertThat(MasterPassword.matches(null, "pbkdf2$1$c2Fs$Y2hhdmU")).isFalse();
        assertThat(MasterPassword.matches(SENHA.toCharArray(), null)).isFalse();
    }

    @Test
    void senhaCurtaNaoEhCadastrada() {
        assertThatThrownBy(() -> MasterPassword.derive("curta".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(MasterPassword.MIN_LENGTH));
    }
}
