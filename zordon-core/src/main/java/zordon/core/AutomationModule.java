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

import java.time.Clock;
import zordon.core.automation.AutomationEngine;
import zordon.core.automation.AutomationNotifier;
import zordon.core.automation.AutomationTools;
import zordon.core.automation.WorkflowEngine;

/** Automações (SPEC-025): o fluxo, o motor com os gatilhos e o aviso. */
final class AutomationModule {

    private final AutomationEngine automations;

    AutomationModule(CoreBase base, TrustModule trust, ToolModule tools, MemoryModule memory, AgentModule agents,
            MonitorModule monitor) {
        AutomationNotifier notifier = new AutomationNotifier(trust.notifications(), tools.windows()::notify,
                Clock.systemDefaultZone());
        WorkflowEngine workflow = new WorkflowEngine(memory.database().tasks(), memory.database().automations(),
                new WorkflowEngine.Engines(tools.tools(), agents.registry(), agents.runner(), notifier),
                base.bus(), Clock.systemDefaultZone(), System::nanoTime);
        this.automations = new AutomationEngine(base.config().home().resolve("automations"),
                new AutomationEngine.Stores(memory.database().automations(), memory.database().tasks()),
                new AutomationEngine.Engines(tools.tools(), agents.registry(), workflow, notifier),
                new AutomationEngine.Env(base.bus(), trust.lockdown()::active, monitor.sampler()::latest),
                Clock.systemDefaultZone(), System::nanoTime);
        tools.tools().register(AutomationTools.propose(automations));
    }

    AutomationEngine automations() {
        return automations;
    }
}
