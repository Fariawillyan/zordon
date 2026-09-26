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
package zordon.desktop;

import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.ui.ShellMemoryActions;

/** A memória e a base de conhecimento pedidas pela tela. */
final class DesktopMemoryActions implements ShellMemoryActions {

    private final DesktopContext context;
    private final DesktopState state;

    DesktopMemoryActions(DesktopContext context) {
        this.context = context;
        this.state = context.state();
    }

    @Override
    public void loadMemory() {
        context.request("memory.facts", Map.of("limit", 200))
                .thenAccept(result -> Platform.runLater(() -> {
                    state.resources().memoryFacts().clear();
                    if (result.get("facts") instanceof List<?> facts) {
                        facts.stream().filter(Map.class::isInstance).forEach(fact -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) fact;
                            state.resources().memoryFacts().add(typed);
                        });
                    }
                }))
                .exceptionally(failure -> null);
    }

    @Override
    public void forgetFact(String factId) {
        context.request("memory.forget", Map.of("factId", factId))
                .thenAccept(result -> loadMemory())
                .exceptionally(context::reportFailure);
    }

    @Override
    public void loadKnowledge() {
        context.request("rag.status", Map.of())
                .thenAccept(result -> Platform.runLater(() -> state.resources().knowledgeProperty().set(Map.copyOf(result))))
                .exceptionally(context::reportFailure);
    }

    @Override
    public void reindexKnowledge() {
        context.request("rag.reindex", Map.of())
                .thenAccept(result -> loadKnowledge())
                .exceptionally(context::reportFailure);
    }
}
