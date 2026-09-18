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

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import zordon.ai.AiException;
import zordon.ai.AiMessage;
import zordon.ai.AiProvider;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.AiStream;
import zordon.ai.AiStreamListener;
import zordon.ai.ContentBlock;
import zordon.ai.Money;
import zordon.ai.Pricing;
import zordon.ai.ProviderInfo;
import zordon.api.TokenUsage;
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

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Uma tentativa e duas retentativas, como na política de falhas do núcleo (Core §4). */
    private static final int MAX_ATTEMPTS = 3;

    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(30);
    private static final int CHARS_PER_TOKEN = 4;

    private final String id;
    private final URI baseUrl;
    private final String apiKey;
    private final String maxTokensParam;
    private final Pricing pricing;
    private final ProviderInfo info;

    /**
     * @param apiKey pode faltar: um servidor local normalmente não pede chave
     */
    public OpenAiCompatibleProvider(String id, URI baseUrl, String apiKey, String maxTokensParam, Pricing pricing) {
        this.id = Objects.requireNonNull(id, "id");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiKey = apiKey;
        this.maxTokensParam = Objects.requireNonNull(maxTokensParam, "maxTokensParam");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        this.info = new ProviderInfo(
                id,
                Set.of(ProviderInfo.Capability.STREAMING, ProviderInfo.Capability.TOOLS),
                List.of(),
                isLoopback(baseUrl));
    }

    @Override
    public ProviderInfo info() {
        return info;
    }

    @Override
    public AiResponse chat(AiRequest request) {
        try {
            return stream(request, new AiStreamListener() {}).result().join();
        } catch (java.util.concurrent.CompletionException e) {
            throw e.getCause() instanceof AiException typed
                    ? typed
                    : new AiException(AiException.Kind.UNAVAILABLE, "falha inesperada no provider " + id, e);
        }
    }

    @Override
    public AiStream stream(AiRequest request, AiStreamListener listener) {
        var result = new CompletableFuture<AiResponse>();
        var cancelled = new AtomicBoolean();
        // Um cliente por streaming, para que cancelar possa derrubá-lo inteiro:
        // fechar o corpo nem sempre acorda uma leitura bloqueada no cliente do JDK,
        // e um cancelamento que não aborta deixa a geração — e o custo — correndo.
        HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

        Thread.ofVirtual().name("openai-compat-" + id).start(() -> {
            try (http) {
                new Exchange(request, listener, http, cancelled, result).run();
            }
        });

        return new AiStream() {
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
        };
    }

    /** Este protocolo não tem contagem sem geração; o que existe é estimativa, declarada como tal. */
    @Override
    public long countTokens(AiRequest request) {
        return estimateInput(request);
    }

    /** Uma conversa com o servidor, com retentativa e cancelamento. */
    private final class Exchange {

        private final AiRequest request;
        private final AiStreamListener listener;
        private final HttpClient http;
        private final AtomicBoolean cancelled;
        private final CompletableFuture<AiResponse> result;
        private final ChatCompletionsAccumulator accumulator;
        private final Instant started = Instant.now();

        private Exchange(
                AiRequest request,
                AiStreamListener listener,
                HttpClient http,
                AtomicBoolean cancelled,
                CompletableFuture<AiResponse> result) {
            this.request = request;
            this.listener = listener;
            this.http = http;
            this.cancelled = cancelled;
            this.result = result;
            this.accumulator = new ChatCompletionsAccumulator(listener);
        }

        void run() {
            try {
                for (int attempt = 1; ; attempt++) {
                    HttpResponse<InputStream> response = http.send(httpRequest(), HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() == 200) {
                        readStream(response.body());
                        finish();
                        return;
                    }
                    String body = readBounded(response.body());
                    if (!isRetryable(response.statusCode(), body) || attempt == MAX_ATTEMPTS) {
                        fail(OpenAiErrors.fromStatus(response.statusCode(), body, id));
                        return;
                    }
                    if (!waitBeforeRetry(response, attempt)) {
                        return;
                    }
                }
            } catch (IOException e) {
                if (cancelled.get()) {
                    finishCancelled();
                } else {
                    fail(OpenAiErrors.fromIo(e, baseUrl));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finishCancelled();
            } catch (AiException e) {
                fail(e);
            } catch (RuntimeException e) {
                fail(new AiException(AiException.Kind.UNAVAILABLE, "resposta inesperada de " + baseUrl, e));
            }
        }

        private HttpRequest httpRequest() {
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

        /** Server-Sent Events: linhas {@code data: …}, terminadas por {@code data: [DONE]}. */
        private void readStream(InputStream body) throws IOException {
            try (var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).strip();
                    if ("[DONE]".equals(data)) {
                        return;
                    }
                    if (data.isEmpty()) {
                        continue;
                    }
                    JsonNode chunk = ChatCompletionsRequests.MAPPER.readTree(data);
                    if (chunk.has("error")) {
                        throw OpenAiErrors.fromStreamError(chunk.get("error"), id);
                    }
                    accumulator.accept(chunk);
                }
            }
        }

        /** 429 de limite e 5xx merecem outra tentativa; 429 de crédito não — pagar resolve, esperar não. */
        private boolean isRetryable(int status, String body) {
            boolean quota = body != null && body.toLowerCase(Locale.ROOT).contains("insufficient_quota");
            return (status == 429 && !quota) || status >= 500;
        }

        private boolean waitBeforeRetry(HttpResponse<?> response, int attempt) throws InterruptedException {
            long waitMillis = response.headers().firstValue("retry-after")
                    .map(OpenAiCompatibleProvider::parseSeconds)
                    .orElseGet(() -> (long) (500L * (1L << (attempt - 1)) * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble())));
            long deadline = System.nanoTime() + Duration.ofMillis(Math.min(waitMillis, MAX_RETRY_WAIT.toMillis())).toNanos();
            while (System.nanoTime() < deadline) {
                if (cancelled.get()) {
                    finishCancelled();
                    return false;
                }
                Thread.sleep(25);
            }
            return true;
        }

        private void finish() {
            TokenUsage usage = accumulator.reportedUsage().orElse(null);
            boolean estimated = usage == null;
            if (estimated) {
                usage = new TokenUsage(estimateInput(request), estimateTokens(accumulator.text()), 0, 0);
            }
            // Local não custa dinheiro — é a máquina do usuário trabalhando.
            Money cost = info.local() ? Money.ZERO : pricing.costOf(request.model(), usage);
            AiResponse response = new AiResponse(
                    accumulator.content(),
                    accumulator.stopReason(),
                    usage,
                    cost,
                    request.model(),
                    Duration.between(started, Instant.now()),
                    accumulator.refusalExplanation(),
                    estimated);
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

    private static long estimateInput(AiRequest request) {
        long chars = request.systemPrompt().length();
        for (AiMessage message : request.messages()) {
            for (ContentBlock block : message.content()) {
                chars += switch (block) {
                    case ContentBlock.Text part -> part.text().length();
                    case ContentBlock.ToolResult part -> part.content().length();
                    case ContentBlock.ToolUse part -> part.arguments() == null ? 0 : part.arguments().toString().length();
                    case ContentBlock.Thinking part -> 0;
                };
            }
        }
        return estimateTokens(chars);
    }

    private static long estimateTokens(String text) {
        return estimateTokens(text.length());
    }

    private static long estimateTokens(long chars) {
        return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    private static Long parseSeconds(String value) {
        try {
            return Math.max(0, Long.parseLong(value.strip())) * 1000;
        } catch (NumberFormatException e) {
            return 1000L;
        }
    }

    private static String readBounded(InputStream body) throws IOException {
        try (body) {
            return new String(body.readNBytes(64 * 1024), StandardCharsets.UTF_8);
        }
    }
}
