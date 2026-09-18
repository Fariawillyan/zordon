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
package zordon.ai.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import zordon.ai.AiMessage;
import zordon.ai.AiRequest;
import zordon.ai.ContentBlock;
import zordon.ai.Effort;
import zordon.ai.Role;
import zordon.ai.ToolSpec;
import zordon.api.trace.AcceptanceCriteria;

class ChatCompletionsRequestsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AcceptanceCriteria("SPEC-004/CA-12")
    @Test
    void semEsforcoConfiguradoNenhumEsforcoEhEnviado() {
        // Um modelo que não raciocina devolve 400 ao receber reasoning_effort.
        JsonNode body = ChatCompletionsRequests.toBody(base().build(), "max_completion_tokens");

        assertThat(body.has("reasoning_effort")).isFalse();
    }

    @AcceptanceCriteria("SPEC-004/CA-12")
    @Test
    void esforcoAcimaDeHighViraHighPorqueEhOMaiorQueOProtocoloConhece() {
        assertThat(ChatCompletionsRequests.toBody(base().effort(Effort.XHIGH).build(), "max_completion_tokens")
                        .get("reasoning_effort").asText())
                .isEqualTo("high");
        assertThat(ChatCompletionsRequests.toBody(base().effort(Effort.LOW).build(), "max_completion_tokens")
                        .get("reasoning_effort").asText())
                .isEqualTo("low");
    }

    @Test
    void oNomeDoLimiteDeSaidaSegueOServidor() {
        assertThat(ChatCompletionsRequests.toBody(base().build(), "max_tokens").has("max_tokens")).isTrue();
        assertThat(ChatCompletionsRequests.toBody(base().build(), "max_completion_tokens").has("max_completion_tokens"))
                .isTrue();
    }

    @Test
    void oPromptDeSistemaVemPrimeiroEOConsumoEhPedido() {
        JsonNode body = ChatCompletionsRequests.toBody(base().build(), "max_completion_tokens");

        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(body.get("stream_options").get("include_usage").asBoolean()).isTrue();
        assertThat(body.get("stream").asBoolean()).isTrue();
    }

    @AcceptanceCriteria("SPEC-004/CA-13")
    @Test
    void chamadaEResultadoDeFerramentaViramMensagensDoProtocolo() throws Exception {
        AiMessage call = new AiMessage(Role.ASSISTANT, List.of(
                new ContentBlock.Text("vou olhar"),
                new ContentBlock.ToolUse("call_1", "clima", MAPPER.readTree("{\"cidade\":\"Recife\"}"))));
        AiMessage result = new AiMessage(Role.USER, List.of(
                new ContentBlock.ToolResult("call_1", "31 graus", false)));

        JsonNode messages = ChatCompletionsRequests.toBody(
                        base().messages(List.of(AiMessage.user("e o tempo?"), call, result)).build(),
                        "max_completion_tokens")
                .get("messages");

        JsonNode assistant = messages.get(2);
        assertThat(assistant.get("role").asText()).isEqualTo("assistant");
        assertThat(assistant.get("tool_calls").get(0).get("id").asText()).isEqualTo("call_1");
        // Argumentos como texto JSON: é o que o protocolo exige.
        assertThat(assistant.get("tool_calls").get(0).get("function").get("arguments").asText())
                .isEqualTo("{\"cidade\":\"Recife\"}");

        JsonNode tool = messages.get(3);
        assertThat(tool.get("role").asText()).isEqualTo("tool");
        assertThat(tool.get("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(tool.get("content").asText()).isEqualTo("31 graus");
    }

    @AcceptanceCriteria("SPEC-004/CA-13")
    @Test
    void resultadoDeErroNaoEhOmitidoEVaiMarcado() {
        AiMessage result = new AiMessage(Role.USER, List.of(new ContentBlock.ToolResult("call_1", "timeout", true)));

        JsonNode tool = ChatCompletionsRequests.toBody(base().messages(List.of(result)).build(), "max_tokens")
                .get("messages").get(1);

        // Omitir o resultado deixa o modelo esperando algo que nunca chega (Core §5).
        assertThat(tool.get("content").asText()).isEqualTo("ERRO: timeout");
    }

    @AcceptanceCriteria("SPEC-004/CA-13")
    @Test
    void ferramentasVaoEmOrdemEstavelNoFormatoDeFuncao() {
        var schema = MAPPER.createObjectNode().put("type", "object");
        JsonNode tools = ChatCompletionsRequests.toBody(
                        base().tools(List.of(new ToolSpec("zeta", "z", schema), new ToolSpec("alfa", "a", schema))).build(),
                        "max_tokens")
                .get("tools");

        assertThat(tools.get(0).get("type").asText()).isEqualTo("function");
        assertThat(tools.get(0).get("function").get("name").asText()).isEqualTo("alfa");
        assertThat(tools.get(1).get("function").get("name").asText()).isEqualTo("zeta");
    }

    private static AiRequest.Builder base() {
        return AiRequest.builder("modelo").systemPrompt("Você é o Zordon.").messages(List.of(AiMessage.user("oi")));
    }
}
