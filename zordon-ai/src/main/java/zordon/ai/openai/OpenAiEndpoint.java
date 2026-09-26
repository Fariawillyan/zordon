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

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.Money;
import zordon.ai.Pricing;
import zordon.api.TokenUsage;

/**
 * O servidor e como falar com ele. Classe, não record: a chave não pode
 * aparecer num {@code toString()} que alguém um dia registre.
 */
final class OpenAiEndpoint {

    private final String id;
    private final URI baseUrl;
    private final String apiKey;
    private final String maxTokensParam;
    private final Pricing pricing;
    private final boolean local;

    /** @param apiKey pode faltar: um servidor local normalmente não pede chave */
    OpenAiEndpoint(String id, URI baseUrl, String apiKey, String maxTokensParam, Pricing pricing, boolean local) {
        this.id = id;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.maxTokensParam = maxTokensParam;
        this.pricing = pricing;
        this.local = local;
    }

    String id() {
        return id;
    }

    URI baseUrl() {
        return baseUrl;
    }

    HttpRequest httpRequest(AiRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(request.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        ChatCompletionsRequests.toBody(request, maxTokensParam).toString(), StandardCharsets.UTF_8));
        if (apiKey != null) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    /** A resposta final; sem uso informado pelo servidor, o uso é estimado e marcado como tal. */
    AiResponse response(AiRequest request, ChatCompletionsAccumulator accumulator, Instant started) {
        TokenUsage usage = accumulator.reportedUsage().orElse(null);
        boolean estimated = usage == null;
        if (estimated) {
            usage = new TokenUsage(TokenEstimate.input(request), TokenEstimate.tokens(accumulator.text()), 0, 0);
        }
        // Local não custa dinheiro — é a máquina do usuário trabalhando.
        Money cost = local ? Money.ZERO : pricing.costOf(request.model(), usage);
        return new AiResponse(
                accumulator.content(),
                accumulator.stopReason(),
                usage,
                cost,
                request.model(),
                Duration.between(started, Instant.now()),
                accumulator.refusalExplanation(),
                estimated);
    }
}
