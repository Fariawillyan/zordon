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
package zordon.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.tomlj.Toml;
import zordon.api.event.EventType;
import zordon.core.chat.ConversationStore;
import zordon.core.memory.Distiller;
import zordon.core.memory.MemoryContext;
import zordon.core.memory.MemoryTools;
import zordon.core.memory.WorkObserver;
import zordon.memory.Fact;
import zordon.memory.MemoryStore;
import zordon.memory.ZordonDatabase;

/** Memória de longo prazo e destilação (SPEC-021). */
final class MemoryModule {

    private final ZordonDatabase database;
    private final MemoryStore memory;
    private final Distiller distiller;

    MemoryModule(CoreBase base, ToolModule tools) {
        this.database = new ZordonDatabase(base.config().home().resolve("zordon.db"), Clock.systemUTC());
        this.memory = database.memory();
        Consumer<Fact> remembered = fact -> base.bus().publish(EventType.MEMORY_WRITTEN,
                Map.of("kind", fact.kind().name(), "id", fact.id(), "summary",
                        fact.content().length() > 80 ? fact.content().substring(0, 80) + "…" : fact.content()));
        base.conversations().recordTo(new ConversationStore.Recorder() {
            @Override
            public void session(String sessionId, String title, Instant startedAt) {
                memory.session(sessionId, title, startedAt);
            }

            @Override
            public void message(String sessionId, String turnId, String role, String text, Instant ts) {
                memory.message(sessionId, turnId, role, text, ts);
            }
        });
        tools.tools().register(MemoryTools.remember(memory, Clock.systemUTC(), remembered))
                .register(MemoryTools.search(memory, Clock.systemUTC(), ZoneId.systemDefault()))
                .onCompleted(new WorkObserver(memory, tools.policy().workspaceRoots(), Clock.systemUTC(),
                        ZoneId.systemDefault(), remembered)::completed);
        base.turns().hooks().onRecall(new MemoryContext(memory, Clock.systemUTC(), ZoneId.systemDefault()));
        this.distiller = new Distiller(memory, base.providers(), base.redactor(), Clock.systemUTC(),
                distillEnabled(base.config().home().resolve("config.toml")), remembered);
    }

    ZordonDatabase database() {
        return database;
    }

    MemoryStore memory() {
        return memory;
    }

    Distiller distiller() {
        return distiller;
    }

    /** O diagnóstico da memória, com as tarefas junto. */
    Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>(memory.stats());
        stats.put("tasks", database.tasks().taskStats());
        return stats;
    }

    /** {@code [memory] distill = false} desliga a destilação; o padrão é ligada (SPEC-021 §3). */
    private static boolean distillEnabled(Path configToml) {
        if (!Files.exists(configToml)) {
            return true;
        }
        try {
            Boolean distill = Toml.parse(configToml).getBoolean("memory.distill");
            return distill == null || distill;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }
}
