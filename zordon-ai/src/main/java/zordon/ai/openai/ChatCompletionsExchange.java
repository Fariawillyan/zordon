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

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import zordon.ai.AiException;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.AiStream;
import zordon.ai.AiStreamListener;

/** Uma conversa com o servidor, com retentativa e cancelamento. É o próprio streaming devolvido a quem pediu. */
final class ChatCompletionsExchange implements AiStream {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Uma tentativa e duas retentativas, como na política de falhas do núcleo (Core §4). */
    private static final int MAX_ATTEMPTS = 3;

    private final OpenAiEndpoint endpoint;
    private final AiRequest request;
    private final AiStreamListener listener;
    // Um cliente por streaming, para que cancelar possa derrubá-lo inteiro:
    // fechar o corpo nem sempre acorda uma leitura bloqueada no cliente do JDK,
    // e um cancelamento que não aborta deixa a geração — e o custo — correndo.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CompletableFuture<AiResponse> result = new CompletableFuture<>();
    private final ChatCompletionsAccumulator accumulator;
    private final Instant started = Instant.now();

    ChatCompletionsExchange(OpenAiEndpoint endpoint, AiRequest request, AiStreamListener listener) {
        this.endpoint = endpoint;
        this.request = request;
        this.listener = listener;
        this.accumulator = new ChatCompletionsAccumulator(listener);
    }

    @Override
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            http.shutdownNow();
        }
    }

    @Override
    public CompletableFuture<AiResponse> result() {
        return result;
    }

    void run() {
        try (http) {
            exchange();
        }
    }

    private void exchange() {
        try {
            for (int attempt = 1; ; attempt++) {
                HttpResponse<InputStream> response =
                        http.send(endpoint.httpRequest(request), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 200) {
                    ServerSentEvents.read(response.body(), accumulator, endpoint.id());
                    finish();
                    return;
                }
                String body = OpenAiRetry.readBounded(response.body());
                if (!OpenAiRetry.isRetryable(response.statusCode(), body) || attempt == MAX_ATTEMPTS) {
                    fail(OpenAiErrors.fromStatus(response.statusCode(), body, endpoint.id()));
                    return;
                }
                if (!OpenAiRetry.waitBeforeRetry(response, attempt, cancelled::get)) {
                    finishCancelled();
                    return;
                }
            }
        } catch (IOException e) {
            if (cancelled.get()) {
                finishCancelled();
            } else {
                fail(OpenAiErrors.fromIo(e, endpoint.baseUrl()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishCancelled();
        } catch (AiException e) {
            fail(e);
        } catch (RuntimeException e) {
            fail(new AiException(AiException.Kind.UNAVAILABLE, "resposta inesperada de " + endpoint.baseUrl(), e));
        }
    }

    private void finish() {
        AiResponse response = endpoint.response(request, accumulator, started);
        listener.onDone(response.stopReason());
        result.complete(response);
    }

    private void finishCancelled() {
        accumulator.cancelled();
        finish();
    }

    private void fail(AiException error) {
        listener.onError(error);
        result.completeExceptionally(error);
    }
}
