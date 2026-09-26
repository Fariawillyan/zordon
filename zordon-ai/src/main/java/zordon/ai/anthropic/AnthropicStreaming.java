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
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.RawMessageStreamEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.ai.AiException;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.AiStream;
import zordon.ai.AiStreamListener;
import zordon.ai.Money;
import zordon.ai.Pricing;

/** Um streaming Anthropic: a thread de leitura, o cancelamento e o desfecho. */
final class AnthropicStreaming {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

    private final AiRequest request;
    private final AiStreamListener listener;
    private final Pricing pricing;
    private final StreamAccumulator accumulator;
    private final CompletableFuture<AiResponse> result = new CompletableFuture<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Instant started = Instant.now();

    private AnthropicStreaming(AiRequest request, AiStreamListener listener, Pricing pricing) {
        this.request = request;
        this.listener = listener;
        this.pricing = pricing;
        this.accumulator = new StreamAccumulator(listener);
    }

    static AiStream open(AnthropicClient client, Pricing pricing, String endpoint, AiRequest request,
            AiStreamListener listener) {
        AnthropicStreaming stream = new AnthropicStreaming(request, listener, pricing);
        Thread worker = Thread.ofVirtual().name("anthropic-stream").unstarted(() -> stream.read(client, endpoint));
        worker.start();

        return new AiStream() {
            /**
             * Interromper a thread de leitura é o que aborta de fato. Fechar a
             * resposta do SDK a partir de outra thread não acorda a leitura em curso:
             * o fechamento espera o fluxo terminar sozinho, e o kit de contrato
             * mediu isso travando por dez minutos. Numa thread virtual, a
             * interrupção fecha o socket na hora — a geração para, e o custo também.
             */
            @Override
            public void cancel() {
                if (stream.cancelled.compareAndSet(false, true)) {
                    worker.interrupt();
                }
            }

            @Override
            public CompletableFuture<AiResponse> result() {
                return stream.result;
            }
        };
    }

    private void read(AnthropicClient client, String endpoint) {
        try (StreamResponse<RawMessageStreamEvent> response =
                client.messages().createStreaming(AnthropicRequests.toParams(request))) {
            response.stream().forEach(accumulator::accept);
            finish();
        } catch (RuntimeException e) {
            if (cancelled.get()) {
                accumulator.cancelled();
                finish();
                return;
            }
            AiException failure = AnthropicErrors.translate(e, endpoint);
            listener.onError(failure);
            result.completeExceptionally(failure);
        }
    }

    private void finish() {
        Money cost = pricing.costOf(request.model(), accumulator.usage());
        AiResponse response = new AiResponse(
                accumulator.content(),
                accumulator.stopReason(),
                accumulator.usage(),
                cost,
                request.model(),
                Duration.between(started, Instant.now()),
                accumulator.refusalExplanation(),
                false);

        if (accumulator.usage().cacheReadTokens() == 0 && request.cacheSystemPrompt()) {
            // Zero leitura de cache em requisições repetidas significa prefixo
            // instável — o maior risco do M1 (docs/roadmap.md).
            log.debug("cache de prompt não aproveitado neste turno ({} tokens de entrada)",
                    accumulator.usage().inputTokens());
        }
        listener.onDone(response.stopReason());
        result.complete(response);
    }
}
