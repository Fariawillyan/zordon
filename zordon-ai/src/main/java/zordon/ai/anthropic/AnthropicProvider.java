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

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCountTokensParams;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import zordon.ai.AiException;
import zordon.ai.AiProvider;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.AiStream;
import zordon.ai.AiStreamListener;
import zordon.ai.Pricing;
import zordon.ai.ProviderInfo;
import zordon.ai.StopReason;
import zordon.api.trace.Spec;

/**
 * Adaptador do provider Anthropic.
 *
 * <p>Todo caminho de conversa usa streaming: {@code maxTokens} alto sem streaming
 * estoura o tempo limite do HTTP antes de a resposta terminar
 * ([Core §1](../../../../../docs/specs/core/design.md#1-abstração-de-provider)).
 */
@Spec("SPEC-003")
public final class AnthropicProvider implements AiProvider {

    private static final ProviderInfo INFO = new ProviderInfo(
            "anthropic",
            Set.of(
                    ProviderInfo.Capability.STREAMING,
                    ProviderInfo.Capability.TOOLS,
                    ProviderInfo.Capability.VISION,
                    ProviderInfo.Capability.THINKING,
                    ProviderInfo.Capability.PROMPT_CACHE,
                    ProviderInfo.Capability.EFFORT),
            List.of("claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5"),
            // Não é local: é exatamente isto que o PermissionEngine consulta antes de
            // deixar conteúdo confidencial entrar em um prompt.
            false);

    private final AnthropicClient client;
    private final Pricing pricing;
    private final String endpoint;

    public AnthropicProvider(AnthropicClient client, Pricing pricing) {
        this(client, pricing, AnthropicErrors.DEFAULT_ENDPOINT);
    }

    private AnthropicProvider(AnthropicClient client, Pricing pricing, String endpoint) {
        this.client = client;
        this.pricing = pricing;
        this.endpoint = endpoint;
    }

    /**
     * Cria o provider com uma chave já resolvida.
     *
     * <p>A chave chega pronta de quem leu a configuração; este adaptador não sabe
     * de onde ela veio, e não precisa saber (ADR-0026).
     */
    public static AnthropicProvider create(String apiKey, java.util.Optional<java.net.URI> baseUrl, Pricing pricing) {
        var client = AnthropicOkHttpClient.builder().apiKey(apiKey);
        baseUrl.ifPresent(url -> client.baseUrl(url.toString()));
        return new AnthropicProvider(
                client.build(), pricing, baseUrl.map(java.net.URI::toString).orElse(AnthropicErrors.DEFAULT_ENDPOINT));
    }

    @Override
    public ProviderInfo info() {
        return INFO;
    }

    @Override
    public AiResponse chat(AiRequest request) {
        Instant started = Instant.now();
        try {
            var message = client.messages().create(AnthropicRequests.toParams(request));
            return MessageResponses.toResponse(message, request, pricing, elapsed(started));
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public AiStream stream(AiRequest request, AiStreamListener listener) {
        return AnthropicStreaming.open(client, pricing, endpoint, request, listener);
    }

    @Override
    public long countTokens(AiRequest request) {
        var params = AnthropicRequests.toParams(request);
        var counting = MessageCountTokensParams.builder()
                .model(request.model())
                .messages(params.messages())
                .build();
        try {
            return client.messages().countTokens(counting).inputTokens();
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    private Duration elapsed(Instant started) {
        return Duration.between(started, Instant.now());
    }

    private AiException translate(RuntimeException e) {
        return AnthropicErrors.translate(e, endpoint);
    }

    /** Só para deixar explícito que uma resposta cancelada não é erro. */
    static boolean isCancelled(AiResponse response) {
        return response.stopReason() == StopReason.CANCELLED;
    }
}
