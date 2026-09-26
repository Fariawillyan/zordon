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

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import zordon.ai.AiException;
import zordon.ai.AiProvider;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.AiStream;
import zordon.ai.AiStreamListener;
import zordon.ai.Pricing;
import zordon.ai.ProviderInfo;
import zordon.api.trace.Spec;

/**
 * Provider para qualquer servidor que fale Chat Completions: OpenAI, Ollama,
 * LM Studio, vLLM, OpenRouter, Groq, DeepSeek, o endpoint compatível do Gemini.
 *
 * <p>Escrito sobre o cliente HTTP do JDK, sem SDK: o protocolo é pequeno e
 * estável, e um SDK de fornecedor tenderia a mandar parâmetros que os outros
 * servidores não conhecem (ADR-0026).
 */
@Spec("SPEC-004")
public final class OpenAiCompatibleProvider implements AiProvider {

    private final String id;
    private final OpenAiEndpoint endpoint;
    private final ProviderInfo info;

    /**
     * @param apiKey pode faltar: um servidor local normalmente não pede chave
     */
    public OpenAiCompatibleProvider(String id, URI baseUrl, String apiKey, String maxTokensParam, Pricing pricing) {
        this.id = Objects.requireNonNull(id, "id");
        boolean local = isLoopback(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.endpoint = new OpenAiEndpoint(id, baseUrl, apiKey, Objects.requireNonNull(maxTokensParam, "maxTokensParam"),
                Objects.requireNonNull(pricing, "pricing"), local);
        this.info = new ProviderInfo(
                id,
                Set.of(ProviderInfo.Capability.STREAMING, ProviderInfo.Capability.TOOLS),
                List.of(),
                local);
    }

    @Override
    public ProviderInfo info() {
        return info;
    }

    @Override
    public AiResponse chat(AiRequest request) {
        try {
            return stream(request, new AiStreamListener() {}).result().join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof AiException typed
                    ? typed
                    : new AiException(AiException.Kind.UNAVAILABLE, "falha inesperada no provider " + id, e);
        }
    }

    @Override
    public AiStream stream(AiRequest request, AiStreamListener listener) {
        ChatCompletionsExchange exchange = new ChatCompletionsExchange(endpoint, request, listener);
        Thread.ofVirtual().name("openai-compat-" + id).start(exchange::run);
        return exchange;
    }

    /** Este protocolo não tem contagem sem geração; o que existe é estimativa, declarada como tal. */
    @Override
    public long countTokens(AiRequest request) {
        return TokenEstimate.input(request);
    }

    /**
     * Local é verificado, não declarado: só endereço de loopback conta. Um servidor
     * em outra máquina da rede recebe os dados fora deste computador, e a partir do
     * M3 esta resposta decide se conteúdo confidencial pode ir para ele (ADR-0026).
     */
    static boolean isLoopback(URI address) {
        String host = address.getHost();
        if (host == null) {
            return false;
        }
        host = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        // Só literais de IP: resolver um nome no DNS deixaria a resposta nas mãos de
        // quem controla o DNS.
        if (!host.matches("[0-9.]+|[0-9a-fA-F:]+")) {
            return false;
        }
        try {
            return InetAddress.ofLiteral(host).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
