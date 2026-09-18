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
package zordon.ai.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import zordon.ai.AiMessage;
import zordon.ai.AiRequest;
import zordon.ai.Effort;
import zordon.ai.Thinking;
import zordon.ai.ToolSpec;
import zordon.api.trace.AcceptanceCriteria;

/**
 * Os detalhes da integração que geram bug silencioso quando errados.
 *
 * <p>Cada teste aqui corresponde a uma armadilha documentada em
 * docs/specs/core/design.md §1 — nenhum deles é decoração.
 */
class AnthropicRequestsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AcceptanceCriteria("SPEC-003/CA-9")
    @Test
    void oPromptDeSistemaVaiMarcadoParaCache() {
        MessageCreateParams params = AnthropicRequests.toParams(request().cacheSystemPrompt(true).build());

        List<TextBlockParam> blocks = params.system().orElseThrow().textBlockParams().orElseThrow();

        assertThat(blocks).singleElement().satisfies(block ->
                assertThat(block.cacheControl()).isPresent());
    }

    @AcceptanceCriteria("SPEC-003/CA-9")
    @Test
    void semCacheOBlocoDeSistemaNaoLevaMarcacao() {
        MessageCreateParams params = AnthropicRequests.toParams(request().cacheSystemPrompt(false).build());

        assertThat(params.system().orElseThrow().textBlockParams().orElseThrow())
                .singleElement()
                .satisfies(block -> assertThat(block.cacheControl()).isEmpty());
    }

    @AcceptanceCriteria("SPEC-003/CA-12")
    @Test
    void oEsforcoViaDentroDeOutputConfig() {
        // Esforço no nível de cima da requisição é ignorado em silêncio.
        MessageCreateParams params = AnthropicRequests.toParams(request().effort(Effort.XHIGH).build());

        assertThat(params.outputConfig().flatMap(OutputConfig::effort))
                .hasValue(OutputConfig.Effort.XHIGH);
    }

    @AcceptanceCriteria("SPEC-004/CA-12")
    @Test
    void semEsforcoConfiguradoOutputConfigNaoEhEnviado() {
        // Esforço ausente significa "o padrão da API", e não um valor escolhido por nós.
        MessageCreateParams params = AnthropicRequests.toParams(request().build());

        assertThat(params.outputConfig()).isEmpty();
    }

    @AcceptanceCriteria("SPEC-003/CA-12")
    @Test
    void oPensamentoAdaptativoPedeResumoVisivel() {
        // Sem display=summarized o resumo vem vazio e a interface parece travada
        // durante uma pausa longa de raciocínio.
        MessageCreateParams params = AnthropicRequests.toParams(request().thinking(Thinking.ADAPTIVE).build());

        var adaptive = params.thinking().orElseThrow().adaptive();

        assertThat(adaptive).isPresent();
        assertThat(adaptive.orElseThrow().display()).hasValue(ThinkingConfigAdaptive.Display.SUMMARIZED);
    }

    @AcceptanceCriteria("SPEC-003/CA-12")
    @Test
    void oOrcamentoDePensamentoNuncaEhEnviado() {
        // budget_tokens foi removido nos modelos da geração atual: mandá-lo devolve
        // 400, e o adaptador não pode gerá-lo por acidente.
        MessageCreateParams params = AnthropicRequests.toParams(request().thinking(Thinking.ADAPTIVE).build());

        assertThat(params.thinking().orElseThrow().enabled()).isEmpty();
    }

    @AcceptanceCriteria("SPEC-003/CA-12")
    @Test
    void semPensamentoOParametroEhDesabilitadoExplicitamente() {
        MessageCreateParams params = AnthropicRequests.toParams(request().thinking(Thinking.OFF).build());

        assertThat(params.thinking().orElseThrow().disabled()).isPresent();
    }

    @AcceptanceCriteria("SPEC-003/CA-9")
    @Test
    void asFerramentasVaoEmOrdemDeterminista() {
        // Ordem instável de ferramentas muda o prefixo e zera o cache de prompt.
        var request = request()
                .tools(List.of(tool("zeta.executar"), tool("alfa.ler"), tool("meio.escrever")))
                .build();

        assertThat(AnthropicRequests.toParams(request).tools().orElseThrow())
                .extracting(tool -> tool.tool().orElseThrow().name())
                .containsExactly("alfa.ler", "meio.escrever", "zeta.executar");
    }

    @AcceptanceCriteria("SPEC-003/CA-8")
    @Test
    void oHistoricoChegaNaOrdemEComOsPapeisCertos() {
        var request = request()
                .messages(List.of(
                        AiMessage.user("primeira"),
                        AiMessage.assistant("resposta"),
                        AiMessage.user("segunda")))
                .build();

        assertThat(AnthropicRequests.toParams(request).messages())
                .extracting(message -> message.role().toString())
                .containsExactly("user", "assistant", "user");
    }

    private AiRequest.Builder request() {
        return AiRequest.builder("claude-opus-5")
                .systemPrompt("Você é o Zordon.")
                .messages(List.of(AiMessage.user("oi")));
    }

    private ToolSpec tool(String name) {
        return new ToolSpec(name, "faz algo", MAPPER.createObjectNode().put("type", "object"));
    }
}
