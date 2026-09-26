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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.trace.AcceptanceCriteria;

/** Entrar, sair e guardar a senha do OPPRESSOR MODE (SPEC-036). */
class OppressorServiceTest {

    private static final String SENHA = "abreteSesamo2026";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private final List<Map<String, Object>> entrou = new ArrayList<>();
    private final List<Map<String, Object>> saiu = new ArrayList<>();
    private Path file;

    @BeforeEach
    void setUp() {
        file = dir.resolve("state").resolve("oppressor.hash");
    }

    private OppressorService service() {
        return new OppressorService(file, CLOCK,
                (entered, payload) -> (entered ? entrou : saiu).add(payload));
    }

    private OppressorService comSenha() {
        OppressorService service = service();
        assertThat(service.password(null, SENHA.toCharArray())).isTrue();
        return service;
    }

    @AcceptanceCriteria("SPEC-036/CA-1")
    @Test
    void aSenhaCertaEntraEPublicaOEvento() {
        OppressorService service = comSenha();

        assertThat(service.enter(SENHA.toCharArray(), "desktop")).isTrue();

        assertThat(service.active()).isTrue();
        assertThat(service.status()).containsEntry("active", true).containsEntry("trigger", "desktop")
                .containsEntry("since", "2026-09-23T12:00:00Z");
        assertThat(entrou).singleElement().isEqualTo(
                Map.of("since", "2026-09-23T12:00:00Z", "trigger", "desktop"));
    }

    @AcceptanceCriteria("SPEC-036/CA-2")
    @Test
    void semSenhaCadastradaOuComSenhaErradaNaoEntra() {
        OppressorService semSenha = service();
        assertThat(semSenha.configured()).isFalse();
        assertThat(semSenha.enter(SENHA.toCharArray(), "desktop")).isFalse();
        assertThat(semSenha.active()).isFalse();

        OppressorService service = comSenha();
        assertThat(service.enter("abreteSesamo2025".toCharArray(), "desktop")).isFalse();
        assertThat(service.active()).isFalse();
        assertThat(entrou).isEmpty();
    }

    @AcceptanceCriteria("SPEC-036/CA-5")
    @Test
    void oModoNaoSobreviveAoReinicio() {
        comSenha().enter(SENHA.toCharArray(), "desktop");

        // Outra instância é o que um reinício do núcleo faz.
        OppressorService depois = service();

        assertThat(depois.configured()).as("a senha continua cadastrada").isTrue();
        assertThat(depois.active()).as("o modo, não").isFalse();
    }

    @AcceptanceCriteria("SPEC-036/CA-6")
    @Test
    void sairVoltaAoNormalEPublicaOEvento() {
        OppressorService service = comSenha();
        service.enter(SENHA.toCharArray(), "desktop");

        assertThat(service.exit("desktop")).containsEntry("active", false);

        assertThat(service.active()).isFalse();
        assertThat(saiu).singleElement().isEqualTo(Map.of("by", "desktop"));
        // Sair de novo não republica: o evento conta transição, não chamada.
        service.exit("desktop");
        assertThat(saiu).hasSize(1);
    }

    @AcceptanceCriteria("SPEC-036/CA-7")
    @Test
    void aSenhaEhZeradaDoVetorEnaoApareceNoEstado() {
        OppressorService service = comSenha();
        char[] certa = SENHA.toCharArray();
        char[] errada = "abreteSesamo2025".toCharArray();

        service.enter(errada, "desktop");
        service.enter(certa, "desktop");

        assertThat(certa).as("zerada mesmo quando confere").containsOnly('\0');
        assertThat(errada).as("e também quando não confere").containsOnly('\0');
        assertThat(service.status().toString()).doesNotContain(SENHA);
    }

    @Test
    void aTrocaExigeAAtualEOArquivoNuncaGuardaASenha() throws Exception {
        OppressorService service = comSenha();

        assertThat(service.password("errada000000".toCharArray(), "novaSenha123456".toCharArray())).isFalse();
        assertThat(service.password(SENHA.toCharArray(), "novaSenha123456".toCharArray())).isTrue();

        assertThat(service.enter(SENHA.toCharArray(), "desktop")).isFalse();
        assertThat(service.enter("novaSenha123456".toCharArray(), "desktop")).isTrue();
        assertThat(Files.readString(file)).doesNotContain(SENHA).doesNotContain("novaSenha123456");
    }
}
