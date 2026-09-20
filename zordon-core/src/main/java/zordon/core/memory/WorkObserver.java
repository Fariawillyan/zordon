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

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.ZPath;
import zordon.api.trace.Spec;
import zordon.memory.Fact;
import zordon.memory.FactKind;
import zordon.memory.MemoryStore;
import zordon.memory.NewFact;

/**
 * Trabalho num projeto vira lembrança: uma ferramenta que terminou OK tocando
 * {@code ~/dev/<projeto>/…} grava "trabalhou no projeto X", uma vez por dia
 * (SPEC-021 CA-7). É o que responde "o projeto que trabalhamos ontem".
 */
@Spec("SPEC-021")
public final class WorkObserver {

    static final double CONFIDENCE = 0.9;

    private static final Logger log = LoggerFactory.getLogger(WorkObserver.class);

    private final MemoryStore store;
    private final List<String> workspaces;
    private final Clock clock;
    private final ZoneId zone;
    private final Consumer<Fact> written;

    /** @param workspaces raízes dos workspaces, já expandidas (ex.: {@code /home/u/dev}) */
    public WorkObserver(MemoryStore store, List<String> workspaces, Clock clock, ZoneId zone, Consumer<Fact> written) {
        this.store = store;
        this.workspaces = workspaces.stream().map(root -> root.endsWith("/") ? root : root + "/").toList();
        this.clock = clock;
        this.zone = zone;
        this.written = written;
    }

    public void completed(ActionDescriptor action, String turnId) {
        if (action.tool().startsWith("memory.")) {
            return;
        }
        for (ZPath path : action.touchedPaths()) {
            String wsl = path.toWsl();
            for (String root : workspaces) {
                if (!wsl.startsWith(root) || wsl.length() == root.length()) {
                    continue;
                }
                String rest = wsl.substring(root.length());
                String project = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
                if (!project.isBlank() && !project.startsWith(".")) {
                    record(project, root + project, turnId == null ? "tool:" + action.tool() : turnId);
                }
                return;
            }
        }
    }

    private void record(String project, String where, String provenance) {
        String subject = "projeto " + project;
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        boolean already = store.facts(FactKind.EVENT, subject, 5).stream()
                .anyMatch(fact -> LocalDate.ofInstant(fact.observedAt(), zone).equals(today));
        if (already) {
            return;
        }
        Fact fact = store.remember(new NewFact(FactKind.EVENT, subject,
                "trabalhou no projeto " + project + " em " + today + " (" + where + ")", CONFIDENCE, clock.instant(),
                null, provenance,
                "work", false));
        log.info("memória: trabalho no projeto {} registrado", project);
        written.accept(fact);
    }
}
