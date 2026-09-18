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
package zordon.core.chat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import zordon.ai.AiException;
import zordon.ai.AiProvider;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.AiStream;
import zordon.ai.AiStreamListener;
import zordon.ai.ContentBlock;
import zordon.ai.Money;
import zordon.ai.ProviderInfo;
import zordon.ai.StopReason;
import zordon.api.TokenUsage;

/**
 * Provider roteirizado.
 *
 * <p>Um fake, não um mock: ele devolve uma sequência de fragmentos e o teste
 * verifica <strong>o que aconteceu</strong> — quais eventos o núcleo publicou —, e
 * não como o provider foi chamado (docs/testing/strategy.md §7).
 */
final class FakeAiProvider implements AiProvider {

    private final List<String> deltas;
    private final StopReason stopReason;
    private final AiException failure;
    private final String refusal;
    private final CountDownLatch hold;
    private final List<AiRequest> received = new java.util.concurrent.CopyOnWriteArrayList<>();

    private FakeAiProvider(
            List<String> deltas, StopReason stopReason, AiException failure, String refusal, CountDownLatch hold) {
        this.deltas = deltas;
        this.stopReason = stopReason;
        this.failure = failure;
        this.refusal = refusal;
        this.hold = hold;
    }

    /** Começa a responder e falha no meio — o caso em que não pode haver reserva. */
    static FakeAiProvider failingAfter(String delta, AiException failure) {
        return new FakeAiProvider(List.of(delta), StopReason.END_TURN, failure, null, null);
    }

    static FakeAiProvider answering(String... deltas) {
        return new FakeAiProvider(List.of(deltas), StopReason.END_TURN, null, null, null);
    }

    static FakeAiProvider refusing(String explanation) {
        return new FakeAiProvider(List.of(), StopReason.REFUSAL, null, explanation, null);
    }

    static FakeAiProvider failing(AiException failure) {
        return new FakeAiProvider(List.of(), StopReason.END_TURN, failure, null, null);
    }

    /** Fica pendurado até ser cancelado — é assim que se testa cancelamento sem esperar rede. */
    static FakeAiProvider hanging(CountDownLatch started) {
        return new FakeAiProvider(List.of(), StopReason.END_TURN, null, null, started);
    }

    List<AiRequest> received() {
        return List.copyOf(received);
    }

    @Override
    public ProviderInfo info() {
        return new ProviderInfo("fake", Set.of(ProviderInfo.Capability.STREAMING), List.of("fake-model"), true);
    }

    @Override
    public AiResponse chat(AiRequest request) {
        throw new UnsupportedOperationException("o caminho de conversa usa streaming");
    }

    @Override
    public AiStream stream(AiRequest request, AiStreamListener listener) {
        received.add(request);
        var result = new CompletableFuture<AiResponse>();
        var cancelled = new AtomicBoolean();

        Thread.ofVirtual().start(() -> {
            if (failure != null) {
                deltas.forEach(listener::onTextDelta);
                listener.onError(failure);
                result.completeExceptionally(failure);
                return;
            }
            if (hold != null) {
                hold.countDown();
                while (!cancelled.get()) {
                    Thread.onSpinWait();
                }
                result.complete(response(StopReason.CANCELLED, ""));
                return;
            }
            StringBuilder text = new StringBuilder();
            deltas.forEach(delta -> {
                text.append(delta);
                listener.onTextDelta(delta);
            });
            listener.onDone(stopReason);
            result.complete(response(stopReason, text.toString()));
        });

        return new AiStream() {
            @Override
            public void cancel() {
                cancelled.set(true);
            }

            @Override
            public CompletableFuture<AiResponse> result() {
                return result;
            }
        };
    }

    @Override
    public long countTokens(AiRequest request) {
        return 0;
    }

    private AiResponse response(StopReason reason, String text) {
        return new AiResponse(
                text.isEmpty() ? List.of() : List.of(new ContentBlock.Text(text)),
                reason,
                new TokenUsage(120, 40, 0, 100),
                Money.usd(new java.math.BigDecimal("0.0012")),
                "fake-model",
                Duration.ofMillis(12),
                refusal,
                false);
    }
}
