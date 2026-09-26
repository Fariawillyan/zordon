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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.stream.Collectors;
import zordon.core.change.ChangeTools;
import zordon.core.change.Preflight;
import zordon.core.rag.KnowledgeBase;
import zordon.core.rag.RagTools;
import zordon.core.tasks.Planner;
import zordon.core.tasks.TaskRunner;
import zordon.core.tasks.TaskTools;
import zordon.core.tasks.Verifier;
import zordon.core.usage.UsageTracker;

/** Planos duráveis, conhecimento, preflight e uso de tokens (SPEC-023, SPEC-028, SPEC-029). */
final class TaskModule {

    private final TaskRunner tasks;
    private final KnowledgeBase knowledge;
    private final Preflight preflight;
    private final UsageTracker usage;

    TaskModule(CoreBase base, TrustModule trust, ToolModule tools, MemoryModule memory, AgentModule agents) {
        this.tasks = new TaskRunner(memory.database().tasks(),
                new Planner(base.providers(), agents.registry(), () -> tools.tools().list().stream()
                        .filter(row -> "green".equals(row.get("risk")))
                        .map(row -> String.valueOf(row.get("name")))
                        .filter(name -> tools.tools().resolveWireName(name).isPresent())
                        .collect(Collectors.toSet())),
                new Verifier(base.providers(), tools.tools()),
                new TaskRunner.Agents(agents.registry(), agents.runner()), base.bus(), System::nanoTime);
        tools.tools().register(TaskTools.create(tasks));
        this.knowledge = new KnowledgeBase(memory.database().knowledge(), KnowledgeBase.defaultRoots(
                base.config().home().resolve("config.toml"), repoRoot(base.environment())));
        tools.tools().register(RagTools.search(knowledge));
        this.preflight = new Preflight(memory.database().tasks(), knowledge, agents.registry(), trust.notifications());
        this.usage = new UsageTracker(memory.database().usage(), base.bus(), Clock.systemDefaultZone());
        tools.tools().register(ChangeTools.plan(preflight));
    }

    TaskRunner tasks() {
        return tasks;
    }

    KnowledgeBase knowledge() {
        return knowledge;
    }

    Preflight preflight() {
        return preflight;
    }

    UsageTracker usage() {
        return usage;
    }

    /** O repositório do Zordon no disco, quando ele estiver lá: a fonte da documentação (SPEC-028). */
    private static Path repoRoot(Map<String, String> environment) {
        String declared = environment.get("ZORDON_REPO");
        if (declared != null && Files.isDirectory(Path.of(declared))) {
            return Path.of(declared);
        }
        Path candidate = Path.of(environment.getOrDefault("HOME", System.getProperty("user.home")), "zordon");
        return Files.isDirectory(candidate.resolve("docs")) ? candidate : null;
    }
}
