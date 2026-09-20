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
package zordon.ai.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.ai.Effort;
import zordon.ai.ModelRole;
import zordon.ai.Pricing;
import zordon.api.trace.AcceptanceCriteria;

class AiSettingsTest {

    @TempDir
    Path home;

    @AcceptanceCriteria("SPEC-004/CA-1")
    @Test
    void semArquivoAAssinaturaRespondeEAChaveFicaDeReserva() {
        AiSettings settings = AiSettings.load(home.resolve("config.toml"));

        assertThat(settings.providers()).containsOnlyKeys("claude", "anthropic");
        assertThat(settings.providers().get("claude").precedence())
                .as("a assinatura é tentada antes da chave paga por uso")
                .isLessThan(settings.providers().get("anthropic").precedence());
        assertThat(settings.roles().forRole(ModelRole.CONVERSATION).provider()).isEqualTo("claude");
        assertThat(settings.roles().forRole(ModelRole.CONVERSATION).model()).isEqualTo("opus");
        assertThat(settings.roles().forRole(ModelRole.FALLBACK).provider()).isEqualTo("anthropic");
    }

    @AcceptanceCriteria("SPEC-004/CA-2")
    @Test
    void cadaPapelResolveParaOProviderOModeloEOEsforcoConfigurados() throws Exception {
        AiSettings settings = AiSettings.load(write("""
                [ai.providers.ollama]
                type = "openai-compatible"
                base_url = "http://127.0.0.1:11434/v1"

                [ai.providers.openai]
                type = "openai-compatible"
                base_url = "https://api.openai.com/v1"
                api_key = "env:OPENAI_API_KEY"

                [ai.roles]
                conversation = { provider = "openai", model = "gpt-4.1", effort = "high" }
                fallback = { provider = "ollama", model = "qwen2.5:7b" }
                """));

        var conversation = settings.roles().forRole(ModelRole.CONVERSATION);
        assertThat(conversation.provider()).isEqualTo("openai");
        assertThat(conversation.model()).isEqualTo("gpt-4.1");
        assertThat(conversation.effortIfAny()).hasValue(Effort.HIGH);
        assertThat(settings.roles().forRole(ModelRole.FALLBACK).effortIfAny()).isEmpty();
        assertThat(settings.providers().get("openai").apiKeyIfAny()).hasValue(new SecretRef("OPENAI_API_KEY"));
    }

    @AcceptanceCriteria("SPEC-004/CA-3")
    @Test
    void chaveEscritaLiteralmenteEhRecusadaSemSerRepetida() throws Exception {
        AiSettings settings = AiSettings.load(write("""
                [ai.providers.openai]
                type = "openai-compatible"
                base_url = "https://api.openai.com/v1"
                api_key = "sk-proj-abc123-chave-colada-por-engano"
                """));

        assertThat(settings.providers()).doesNotContainKey("openai");
        assertThat(settings.rejected().get("openai"))
                .contains("env:NOME_DA_VARIAVEL")
                // Repetir a chave na mensagem seria vazá-la no log.
                .doesNotContain("sk-proj");
    }

    @AcceptanceCriteria("SPEC-004/CA-3")
    @Test
    void oProviderRecusadoApareceComoIndisponivelComOMotivo() throws Exception {
        AiSettings settings = AiSettings.load(write("""
                [ai.providers.openai]
                type = "openai-compatible"
                base_url = "https://api.openai.com/v1"
                api_key = "sk-literal"

                [ai.roles]
                conversation = { provider = "openai", model = "gpt-4.1" }
                """));

        var registry = ProviderRegistry.build(settings, Pricing.defaults(), Map.of());

        assertThat(registry.select(ModelRole.CONVERSATION))
                .isInstanceOfSatisfying(ProviderRegistry.Resolution.Unresolved.class,
                        unresolved -> assertThat(unresolved.reason()).contains("env:NOME_DA_VARIAVEL"));
    }

    @Test
    void arquivoComErroDeSintaxeNaoImpedeONucleoDeSubir() throws Exception {
        AiSettings settings = AiSettings.load(write("[ai.providers.x\ntype = "));

        assertThat(settings.providers()).containsOnlyKeys("claude", "anthropic");
    }

    @Test
    void tipoDesconhecidoEhRecusadoDizendoOsTiposValidos() throws Exception {
        AiSettings settings = AiSettings.load(write("""
                [ai.providers.gemini]
                type = "gemini-nativo"
                """));

        assertThat(settings.rejected().get("gemini")).contains("openai-compatible");
    }

    @Test
    void configSemSecaoDeIaUsaOPadrao() throws Exception {
        assertThat(AiSettings.load(write("[core]\nport = 8777\n")).providers())
                .containsOnlyKeys("claude", "anthropic");
    }

    /** O exemplo que o instalador copia. Testado porque exemplo que não funciona é documentação falsa. */
    private static final Path EXAMPLE = Path.of("../packaging/wsl/config.toml.example");

    @AcceptanceCriteria("SPEC-004/CA-1")
    @Test
    void oExemploInstaladoNaoMudaNadaEnquantoEstiverComentado() {
        // Reinstalar copia este arquivo; se ele ativasse algo, mudaria o modelo de quem já usa.
        AiSettings settings = AiSettings.load(EXAMPLE);

        assertThat(settings.providers()).containsOnlyKeys("claude", "anthropic");
        assertThat(settings.rejected()).isEmpty();
    }

    @AcceptanceCriteria("SPEC-004/CA-2")
    @Test
    void oExemploDescomentadoEhConfiguracaoValida() throws Exception {
        String uncommented = Files.readAllLines(EXAMPLE).stream()
                .map(line -> line.matches("^# (\\[|[a-z_]+ +=).*") ? line.substring(2) : line)
                .reduce("", (text, line) -> text + line + "\n");

        AiSettings settings = AiSettings.load(write(uncommented));

        assertThat(settings.rejected()).isEmpty();
        assertThat(settings.providers()).containsKeys("claude", "anthropic", "openai", "ollama", "openrouter");
        // O exemplo ensina a ordem de preferência: a conversa pela assinatura, e a
        // chave paga por uso só como reserva (SPEC-018 §3).
        assertThat(settings.roles().forRole(ModelRole.CONVERSATION).provider()).isEqualTo("claude");
        assertThat(settings.roles().forRole(ModelRole.FALLBACK).provider()).isEqualTo("anthropic");
        assertThat(settings.providers().get("ollama").maxTokensParam()).isEqualTo("max_tokens");
    }

    private Path write(String toml) throws Exception {
        return Files.writeString(home.resolve("config.toml"), toml);
    }
}
