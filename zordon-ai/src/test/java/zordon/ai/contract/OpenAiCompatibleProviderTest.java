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
package zordon.ai.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static zordon.ai.contract.OpenAiCompatibleContractTest.chunk;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import zordon.ai.AiException;
import zordon.ai.AiMessage;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.AiStreamListener;
import zordon.ai.Pricing;
import zordon.ai.StopReason;
import zordon.ai.openai.OpenAiCompatibleProvider;
import zordon.api.trace.AcceptanceCriteria;

/**
 * O que é próprio do protocolo compatível: as variações que aparecem entre
 * servidores reais, e que o kit de contrato não tem como generalizar.
 */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class OpenAiCompatibleProviderTest {

    private FakeBackend backend;

    @BeforeEach
    void start() throws Exception {
        backend = FakeBackend.start();
    }

    @AfterEach
    void stop() {
        backend.close();
    }

    @AcceptanceCriteria("SPEC-004/CA-8")
    @Test
    void semConsumoInformadoOConsumoEhEstimadoEMarcado() {
        // Servidores locais antigos não mandam o pedaço de consumo.
        backend.respondWith(FakeBackend.sse(List.of(
                // 40 caracteres → 10 tokens pela regra de quatro caracteres por token.
                chunk("{\"content\":\"" + "abcdefghij".repeat(4) + "\"}", null),
                chunk("{}", "\"stop\""),
                "data: [DONE]")));

        AiResponse response = run(provider("chave"));

        assertThat(response.isUsageEstimated()).isTrue();
        assertThat(response.usage().outputTokens()).isEqualTo(10);
        assertThat(response.usage().inputTokens()).isPositive();
    }

    @AcceptanceCriteria("SPEC-004/CA-13")
    @Test
    void chamadasDeFerramentaEmPedacosSaoRemontadas() {
        backend.respondWith(FakeBackend.sse(List.of(
                chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"clima\",\"arguments\":\"{\\\"cida\"}}]}", null),
                chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"de\\\":\\\"Recife\\\"}\"}}]}", null),
                chunk("{}", "\"tool_calls\""),
                "data: [DONE]")));

        AiResponse response = run(provider("chave"));

        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(response.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.callId()).isEqualTo("call_1");
            assertThat(call.tool()).isEqualTo("clima");
            assertThat(call.arguments().get("cidade").asText()).isEqualTo("Recife");
        });
    }

    @Test
    void raciocinioDoServidorViraResumoDePensamento() {
        backend.respondWith(FakeBackend.sse(List.of(
                chunk("{\"reasoning_content\":\"pensando em voz alta\"}", null),
                chunk("{\"content\":\"pronto\"}", null),
                chunk("{}", "\"stop\""),
                "data: [DONE]")));
        List<String> thoughts = new CopyOnWriteArrayList<>();

        provider("chave").stream(request(), new AiStreamListener() {
            @Override
            public void onThinking(String summary) {
                thoughts.add(summary);
            }
        }).result().join();

        assertThat(thoughts).containsExactly("pensando em voz alta");
    }

    @Test
    void recusaDoModeloViraRefusalComOMotivo() {
        backend.respondWith(FakeBackend.sse(List.of(
                chunk("{\"refusal\":\"Não posso ajudar com isso.\"}", null),
                chunk("{}", "\"stop\""),
                "data: [DONE]")));

        AiResponse response = run(provider("chave"));

        assertThat(response.stopReason()).isEqualTo(StopReason.REFUSAL);
        assertThat(response.refusal()).hasValue("Não posso ajudar com isso.");
    }

    @Test
    void semCreditoNaoEhTentadoDeNovo() {
        // A OpenAI diz "sem crédito" com um 429, igual ao limite de taxa. Tentar de
        // novo seria pagar latência para receber a mesma resposta.
        backend.respondWith(FakeBackend.error(429,
                "{\"error\":{\"message\":\"You exceeded your current quota\",\"type\":\"insufficient_quota\","
                        + "\"code\":\"insufficient_quota\"}}"));

        AiException failure = failureOf(provider("chave"));

        assertThat(failure.kind()).isEqualTo(AiException.Kind.QUOTA_EXHAUSTED);
        assertThat(backend.requestCount()).isEqualTo(1);
    }

    @Test
    void erroNoFormatoDoOllamaAindaMostraAMensagemDoServidor() {
        backend.respondWith(FakeBackend.error(404, "{\"error\":\"model 'qwen9:999b' not found\"}"));

        AiException failure = failureOf(provider(null));

        assertThat(failure.kind()).isEqualTo(AiException.Kind.INVALID_REQUEST);
        assertThat(failure.getMessage()).contains("model 'qwen9:999b' not found");
    }

    @Test
    void semChaveNenhumCabecalhoDeAutorizacaoEhEnviado() {
        backend.respondWith(FakeBackend.sse(List.of(chunk("{\"content\":\"oi\"}", "\"stop\""), "data: [DONE]")));

        run(provider(null));

        assertThat(backend.authorizations()).containsExactly("null");
    }

    @Test
    void comChaveOCabecalhoBearerEhEnviado() {
        backend.respondWith(FakeBackend.sse(List.of(chunk("{\"content\":\"oi\"}", "\"stop\""), "data: [DONE]")));

        run(provider("chave-secreta"));

        assertThat(backend.authorizations()).containsExactly("Bearer chave-secreta");
    }

    @AcceptanceCriteria("SPEC-004/CA-4")
    @Test
    void localEhVerificadoPeloEnderecoENaoDeclarado() {
        assertThat(localityOf("http://127.0.0.1:11434/v1")).isTrue();
        assertThat(localityOf("http://localhost:1234/v1")).isTrue();
        assertThat(localityOf("http://[::1]:8000/v1")).isTrue();
        // Outra máquina da rede: os dados saem deste computador.
        assertThat(localityOf("http://192.168.0.20:11434/v1")).isFalse();
        assertThat(localityOf("https://api.openai.com/v1")).isFalse();
        // Um nome que "parece" local não é verificado pelo DNS: não conta.
        assertThat(localityOf("http://localhost.exemplo.com/v1")).isFalse();
    }

    @Test
    void servidorComCustoZeroQuandoLocal() {
        backend.respondWith(FakeBackend.sse(List.of(
                chunk("{\"content\":\"oi\"}", "\"stop\""),
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1000000,\"completion_tokens\":1000000}}",
                "data: [DONE]")));

        // Mesmo com um modelo que teria preço na tabela, local é a máquina do usuário.
        AiResponse response = new OpenAiCompatibleProvider(
                        "ollama", URI.create(backend.url() + "/v1"), null, "max_tokens", Pricing.defaults())
                .stream(request("claude-opus-5"), new AiStreamListener() {})
                .result()
                .join();

        assertThat(response.cost().amount()).isZero();
    }

    private boolean localityOf(String url) {
        return new OpenAiCompatibleProvider("x", URI.create(url), null, "max_tokens", Pricing.defaults())
                .info()
                .local();
    }

    private OpenAiCompatibleProvider provider(String key) {
        return new OpenAiCompatibleProvider(
                "teste", URI.create(backend.url() + "/v1"), key, "max_completion_tokens", Pricing.defaults());
    }

    private AiResponse run(OpenAiCompatibleProvider provider) {
        return provider.stream(request(), new AiStreamListener() {}).result().join();
    }

    private AiException failureOf(OpenAiCompatibleProvider provider) {
        try {
            run(provider);
        } catch (CompletionException e) {
            return (AiException) e.getCause();
        }
        throw new AssertionError("deveria ter falhado");
    }

    private static AiRequest request() {
        return request("modelo-de-teste");
    }

    private static AiRequest request(String model) {
        return AiRequest.builder(model)
                .systemPrompt("Você é o Zordon.")
                .messages(List.of(AiMessage.user("oi")))
                .maxOutputTokens(128)
                .timeout(Duration.ofSeconds(20))
                .build();
    }
}
