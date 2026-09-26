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
package zordon.ai.cli;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import zordon.ai.AiException;
import zordon.ai.AiResponse;
import zordon.ai.AiStream;
import zordon.ai.AiStreamListener;

/** Sem streaming de tokens: o texto chega inteiro e sai como um fragmento só (SPEC-018 §3). */
final class CliStream {

    private CliStream() {}

    static AiStream start(Supplier<AiResponse> chat, AiStreamListener listener) {
        CompletableFuture<AiResponse> result = new CompletableFuture<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread.ofVirtual().name("claude-cli").start(() -> {
            try {
                AiResponse response = chat.get();
                if (cancelled.get()) {
                    return;
                }
                if (!response.text().isBlank()) {
                    listener.onTextDelta(response.text());
                }
                response.toolCalls().forEach(listener::onToolUse);
                listener.onUsage(response.usage());
                listener.onDone(response.stopReason());
                result.complete(response);
            } catch (AiException e) {
                if (!cancelled.get()) {
                    listener.onError(e);
                    result.completeExceptionally(e);
                }
            }
        });
        return new AiStream() {
            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    result.completeExceptionally(new AiException(AiException.Kind.CANCELLED, "cancelado"));
                }
            }

            @Override
            public CompletableFuture<AiResponse> result() {
                return result;
            }
        };
    }
}
