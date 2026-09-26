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
package zordon.desktop.ui;

import java.util.EnumMap;
import java.util.Map;
import zordon.desktop.shell.DesktopState;
import zordon.desktop.shell.Destination;

/** Uma tela por destino (SPEC-030): o Painel deixou de prometer o que já existe. */
final class DestinationPages {

    private DestinationPages() {
    }

    static Map<Destination, DestinationPage> create(DesktopState state, ShellActions actions) {
        Map<Destination, DestinationPage> pages = new EnumMap<>(Destination.class);
        pages.put(Destination.TASKS, new TasksView(state, actions));
        pages.put(Destination.AGENTS, new AgentsView(state, actions));
        pages.put(Destination.MCP, new McpView(state, actions));
        pages.put(Destination.SKILLS, new SkillsView(state, actions));
        pages.put(Destination.AUTOMATIONS, new AutomationsView(state, actions));
        pages.put(Destination.MEMORY, new MemoryView(state, actions));
        pages.put(Destination.KNOWLEDGE, new KnowledgeView(state, actions));
        pages.put(Destination.SYSTEM, new SystemView(state, actions));
        pages.put(Destination.USAGE, new UsageView(state, actions));
        pages.put(Destination.SECURITY, new SecurityView(state, actions));
        return pages;
    }
}
