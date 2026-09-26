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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zordon.ai.AiProvider;
import zordon.ai.AiRequest;
import zordon.ai.AiResponse;
import zordon.ai.AiStream;
import zordon.ai.AiStreamListener;
import zordon.ai.ContentBlock;
import zordon.ai.ModelPolicy;
import zordon.ai.Money;
import zordon.ai.ProviderInfo;
import zordon.ai.StopReason;
import zordon.ai.ToolSpec;
import zordon.ai.registry.ProviderRegistry;
import zordon.api.SessionId;
import zordon.api.TokenUsage;
import zordon.api.TurnId;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.api.trace.AcceptanceCriteria;
import zordon.core.event.QueuePolicy;
import zordon.core.event.ZordonEventBus;

/** O laço de ferramentas do turno (SPEC-019). */
class ToolLoopTest {

    private static final ObjectMapper json = new ObjectMapper();

    private final ZordonEventBus bus = new ZordonEventBus("01TESTE00000000000000000000");
    private final ConversationStore conversations = new ConversationStore();
    private final List<EventEnvelope> events = new CopyOnWriteArrayList<>();
    private SessionId session;

    @BeforeEach
    void setUp() {
        bus.subscribe("teste", Set.of(Topic.CHAT), QueuePolicy.dropOldest(256), events::add);
        session = conversations.newSession("teste");
    }

    /** Um provider que responde o roteiro, uma resposta por volta. */
    static final class Scripted implements AiProvider {
        final Deque<AiResponse> script = new ArrayDeque<>();
        final List<AiRequest> received = new CopyOnWriteArrayList<>();

        Scripted then(AiResponse response) {
            script.add(response);
            return this;
        }

        @Override public ProviderInfo info() { return new ProviderInfo("roteiro", Set.of(), List.of("m"), true); }
        @Override public AiResponse chat(AiRequest request) { throw new UnsupportedOperationException(); }
        @Override public long countTokens(AiRequest request) { return 0; }

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

    static AiResponse text(String text) {
        return new AiResponse(List.of(new ContentBlock.Text(text)), StopReason.END_TURN, TokenUsage.NONE, Money.ZERO,
                "m", Duration.ZERO, null, true);
    }

    static AiResponse wants(String... tools) {
        List<ContentBlock> calls = new java.util.ArrayList<>();
        for (int i = 0; i < tools.length; i++) {
            calls.add(new ContentBlock.ToolUse("c" + i, tools[i], json.createObjectNode().put("path", "~")));
        }
        return new AiResponse(calls, StopReason.TOOL_USE, TokenUsage.NONE, Money.ZERO, "m", Duration.ZERO, null, true);
    }

    /** Um executor de ferramentas falso: registra e responde. */
    static final class Tools implements ToolCaller {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> ended = new CopyOnWriteArrayList<>();

        @Override
        public List<ToolSpec> offer(String userText) {
            return List.of(new ToolSpec("system_metrics", "métricas", json.createObjectNode()),
                    new ToolSpec("fs_list", "lista", json.createObjectNode()));
        }

        @Override
        public CompletableFuture<ContentBlock.ToolResult> call(ContentBlock.ToolUse call, String source, String turnId) {
            calls.add(call.tool() + "@" + source);
            boolean known = Set.of("system_metrics", "fs_list").contains(call.tool());
            return CompletableFuture.completedFuture(new ContentBlock.ToolResult(call.callId(),
                    known ? "[dados] disco 40 de 1007 GB" : "Ferramenta inexistente: " + call.tool(), !known));
        }

        @Override
        public void endTurn(String turnId) {
            ended.add(turnId);
        }
    }

    private TurnManager manager(Scripted provider, Tools tools) {
        TurnManager turns = new TurnManager(bus, conversations, new IntentRouter(), new PromptComposer(),
                ProviderRegistry.of(Map.of(ModelPolicy.DEFAULT_PROVIDER, provider), ModelPolicy.defaults()));
        turns.hooks().onToolCalls(tools);
        return turns;
    }

    private Map<String, Object> awaitDone(TurnId turn) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (EventEnvelope event : events) {
                if (event.type() == EventType.AI_RESPONSE && turn.value().equals(event.payload().get("turnId"))
                        && Boolean.TRUE.equals(event.payload().get("done"))) {
                    return event.payload();
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("o turno não terminou");
    }

    @AcceptanceCriteria("SPEC-019/CA-1")
    @Test
    void pedidoDeFerramentaExecutaComAOrigemDoTurnoEOResultadoVoltaAoModelo() throws Exception {
        Scripted provider = new Scripted().then(wants("system_metrics")).then(text("Você usa 40 GB de 1 TB."));
        Tools tools = new Tools();
        TurnId turn = manager(provider, tools).send(session, "quanto de disco eu tenho?", "voice");

        Map<String, Object> done = awaitDone(turn);

        assertThat(done).containsEntry("text", "Você usa 40 GB de 1 TB.");
        assertThat(tools.calls).containsExactly("system_metrics@voice");
        assertThat(provider.received).hasSize(2);
        assertThat(provider.received.getFirst().tools()).extracting(ToolSpec::name)
                .containsExactly("system_metrics", "fs_list");
        AiRequest second = provider.received.getLast();
        assertThat(second.messages().getLast().content()).singleElement()
                .isInstanceOfSatisfying(ContentBlock.ToolResult.class, result ->
                        assertThat(result.content()).contains("disco 40 de 1007 GB"));
        assertThat(tools.ended).containsExactly(turn.value());
    }

    @AcceptanceCriteria("SPEC-019/CA-3")
    @Test
    void passarDoTetoDeChamadasTerminaOTurnoComOAviso() throws Exception {
        Scripted provider = new Scripted();
        for (int i = 0; i < 20; i++) {
            provider.then(wants("fs_list", "fs_list"));
        }
        Tools tools = new Tools();
        TurnId turn = manager(provider, tools).send(session, "liste tudo", "text");

        Map<String, Object> done = awaitDone(turn);

        assertThat(done.get("text")).asString().contains(TurnManager.TOOL_LIMIT_NOTICE);
        assertThat(tools.calls).hasSizeLessThanOrEqualTo(TurnManager.MAX_TOOL_CALLS);
        assertThat(provider.received).hasSizeLessThanOrEqualTo(TurnManager.MAX_STEPS + 1);
    }

    @AcceptanceCriteria("SPEC-019/CA-5")
    @Test
    void ferramentaInexistenteNaoExecutaEOModeloRecebeOErro() throws Exception {
        Scripted provider = new Scripted().then(wants("apagar_tudo")).then(text("Não consigo fazer isso."));
        Tools tools = new Tools();
        TurnId turn = manager(provider, tools).send(session, "apaga tudo aí", "text");
        // "apaga …" é recusado pela rota rápida antes do modelo; aqui o pedido chega por outra frase.
        Map<String, Object> done = awaitDone(turn);
        assertThat(done.get("text")).isNotNull();

        TurnId second = manager(provider, tools).send(session, "faça uma limpeza geral", "text");
        awaitDone(second);
        assertThat(tools.calls).containsExactly("apagar_tudo@text");
        assertThat(provider.received.getLast().messages().getLast().content()).singleElement()
                .isInstanceOfSatisfying(ContentBlock.ToolResult.class, result -> {
                    assertThat(result.isError()).isTrue();
                    assertThat(result.content()).contains("inexistente");
                });
    }
}
