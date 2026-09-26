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
package zordon.core.memory;

import java.util.List;
import java.util.function.Consumer;
import zordon.ai.AiResponse;
import zordon.ai.ModelRole;
import zordon.ai.registry.ProviderRegistry;
import zordon.core.chat.RoleModels;
import zordon.memory.Fact;
import zordon.memory.MemoryStore;
import zordon.memory.NewFact;
import zordon.memory.StoredLine;

/** Um turno vira fatos: o trecho que pode ir ao modelo, o pedido e o que se grava do que volta. */
final class TurnDistillation {

    private final MemoryStore store;
    private final ProviderRegistry providers;
    private final DistilledFacts facts;
    private final Consumer<Fact> written;

    TurnDistillation(MemoryStore store, ProviderRegistry providers, DistilledFacts facts, Consumer<Fact> written) {
        this.store = store;
        this.providers = providers;
        this.facts = facts;
        this.written = written;
    }

    int distill(MemoryStore.DistillJob job) throws Exception {
        List<StoredLine> lines = store.turn(job.turnId());
        String asked = lines.stream().filter(line -> "user".equals(line.role())).map(StoredLine::content)
                .reduce("", (a, b) -> a + b + "\n").strip();
        String answer = lines.stream().filter(line -> "assistant".equals(line.role())).map(StoredLine::content)
                .reduce("", (a, b) -> a + b + "\n").strip();
        if (asked.isEmpty()) {
            return 0;
        }
        StringBuilder excerpt = new StringBuilder("[Pedido do usuário]\n").append(asked);
        if (!job.tainted() && !answer.isEmpty()) {
            // Turno contaminado: a resposta pode repetir o que um arquivo ou página mandou dizer.
            excerpt.append("\n\n[Resposta do Zordon]\n").append(answer);
        }
        ProviderRegistry.Selection selection = RoleModels.first(providers, ModelRole.SUMMARIZE, ModelRole.CONVERSATION)
                .orElseThrow(() -> new IllegalStateException("nenhum modelo disponível para destilar"));
        AiResponse response = RoleModels.ask(selection, Distiller.SYSTEM, excerpt.toString(), 1_024);
        int accepted = 0;
        for (NewFact fact : facts.parse(response.text(), job.turnId())) {
            Fact stored = store.remember(fact);
            written.accept(stored);
            accepted++;
        }
        return accepted;
    }
}
