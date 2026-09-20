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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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

/** Um provider de roteiro para os testes de turno, memória e agentes. */
public final class ToolLoopTestSupport {

    /** Uma resposta por volta no {@code stream}; o {@code chat} tem o próprio roteiro. */
    public static final class Scripted implements AiProvider {
        private final Deque<AiResponse> script = new ArrayDeque<>();
        private final Deque<Object> chatScript = new ArrayDeque<>();
        public final List<AiRequest> received = new CopyOnWriteArrayList<>();
        public final List<AiRequest> chats = new CopyOnWriteArrayList<>();

        public Scripted then(AiResponse response) {
            script.add(response);
            return this;
        }

        public Scripted thenChat(String text) {
            chatScript.add(text(text));
            return this;
        }

        public Scripted thenFail(AiException failure) {
            chatScript.add(failure);
            return this;
        }

        @Override public ProviderInfo info() { return new ProviderInfo("roteiro", Set.of(), List.of("m"), true); }
        @Override public long countTokens(AiRequest request) { return 0; }

        @Override
        public synchronized AiResponse chat(AiRequest request) {
            chats.add(request);
            Object next = chatScript.isEmpty() ? text("[]") : chatScript.poll();
            if (next instanceof AiException failure) {
                throw failure;
            }
            return (AiResponse) next;
        }

        @Override
        public synchronized AiStream stream(AiRequest request, AiStreamListener listener) {
            received.add(request);
            AiResponse next = script.isEmpty() ? text("fim") : script.poll();
            if (!next.text().isBlank()) {
                listener.onTextDelta(next.text());
            }
            CompletableFuture<AiResponse> result = CompletableFuture.completedFuture(next);
            return new AiStream() {
                @Override public void cancel() { }
                @Override public CompletableFuture<AiResponse> result() { return result; }
            };
        }
    }

    public static AiResponse text(String text) {
        return new AiResponse(List.of(new ContentBlock.Text(text)), StopReason.END_TURN, TokenUsage.NONE, Money.ZERO,
                "m", Duration.ZERO, null, true);
    }

    private ToolLoopTestSupport() {}
}
