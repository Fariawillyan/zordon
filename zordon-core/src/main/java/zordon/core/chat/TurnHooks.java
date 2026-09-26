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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import zordon.core.agents.AgentProfile;
import zordon.core.agents.AgentRegistry;

/** O que o turno usa e quem ele avisa: ferramentas, agentes, memória, conclusão e o relógio dos agentes. */
public final class TurnHooks {

    private volatile ToolCaller caller;
    private volatile TurnManager.Recall recall = text -> "";
    private volatile Consumer<TurnManager.Completed> completed = done -> { };
    private volatile AgentRegistry agents;
    private volatile LongSupplier nanos = System::nanoTime;
    private volatile BiConsumer<AgentProfile, String> suspended = (agent, reason) -> { };
    private volatile TurnManager.ToolInvoker tools = (tool, args, source, turnId) ->
            CompletableFuture.completedFuture("As ferramentas ainda não estão disponíveis.");

    TurnHooks() {}

    /** Liga as ferramentas ao modelo (SPEC-019). Sem isto, o modelo só responde texto. */
    public void onToolCalls(ToolCaller toolCaller) {
        this.caller = Objects.requireNonNull(toolCaller, "toolCaller");
    }

    /** Agentes como configuração (SPEC-022). Sem isto, o turno é do agente geral sem teto próprio. */
    public void onAgents(AgentRegistry registry) {
        this.agents = Objects.requireNonNull(registry, "registry");
    }

    /** O disjuntor de um turno abriu: o usuário precisa saber (SPEC-022 CA-5). */
    public void onSuspended(BiConsumer<AgentProfile, String> listener) {
        this.suspended = Objects.requireNonNull(listener, "listener");
    }

    /** O relógio do orçamento de tempo dos agentes. Trocado só nos testes de tempo de parede. */
    public void nanoClock(LongSupplier source) {
        this.nanos = Objects.requireNonNull(source, "source");
    }

    public void onRecall(TurnManager.Recall memory) {
        this.recall = Objects.requireNonNull(memory, "memory");
    }

    public void onCompleted(Consumer<TurnManager.Completed> listener) {
        this.completed = Objects.requireNonNull(listener, "listener");
    }

    public void onTool(TurnManager.ToolInvoker invoker) {
        this.tools = Objects.requireNonNull(invoker, "invoker");
    }

    ToolCaller caller() {
        return caller;
    }

    TurnManager.Recall recall() {
        return recall;
    }

    Consumer<TurnManager.Completed> completed() {
        return completed;
    }

    AgentRegistry agents() {
        return agents;
    }

    LongSupplier nanos() {
        return nanos;
    }

    BiConsumer<AgentProfile, String> suspended() {
        return suspended;
    }

    TurnManager.ToolInvoker tools() {
        return tools;
    }
}
