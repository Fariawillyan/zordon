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

import zordon.core.zwp.AgentMethods;
import zordon.core.zwp.AutomationMethods;
import zordon.core.zwp.DefenseMethods;
import zordon.core.zwp.McpMethods;
import zordon.core.zwp.MemoryMethods;
import zordon.core.zwp.RagMethods;
import zordon.core.zwp.SecurityMethods;
import zordon.core.zwp.TaskMethods;
import zordon.core.zwp.ToolMethods;
import zordon.core.zwp.UsageMethods;

/** Os métodos do protocolo de cada subsistema: segurança, ferramentas, MCP, memória, agentes, tarefas e defesa. */
final class FeatureRegistrar {

    private FeatureRegistrar() {}

    static void register(CoreModules modules) {
        CoreBase base = modules.base();
        TrustModule trust = modules.trust();
        new SecurityMethods(trust.notifications(), trust.lockdown(), trust.oppressor(), trust.approver(),
                trust.check()::state).registerOn(base.server());
        new ToolMethods(modules.tools().tools(), modules.tools().windows(), modules.tools().vault())
                .registerOn(base.server());
        new McpMethods(modules.defense().mcp()).registerOn(base.server());
        new MemoryMethods(modules.memory().memory()).registerOn(base.server());
        new AgentMethods(modules.agents().registry(), modules.agents().runs()).registerOn(base.server());
        modules.monitor().methods().registerOn(base.server());
        new TaskMethods(modules.memory().database().tasks(), modules.tasks().tasks())
                .automations(modules.automation().automations()).registerOn(base.server());
        new AutomationMethods(modules.automation().automations()).registerOn(base.server());
        new UsageMethods(modules.tasks().usage(), modules.tasks().preflight()).registerOn(base.server());
        new RagMethods(modules.tasks().knowledge()).registerOn(base.server());
        new DefenseMethods(modules.response().engine(), modules.defense().defense(), modules.defense().mcp())
                .registerOn(base.server());
    }
}
