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

import java.util.concurrent.atomic.AtomicReference;

/**
 * Os subsistemas do núcleo, montados em ordem: cada um só depende dos que vêm
 * antes. A ordem de encerramento é a inversa do que importa desligar primeiro.
 */
record CoreModules(CoreBase base, VoiceModule voice, TrustModule trust, ToolModule tools, MemoryModule memory,
        DefenseModule defense, AgentModule agents, TaskModule tasks, ResponseModule response, MonitorModule monitor,
        AutomationModule automation) {

    static CoreModules build(CoreBase base) {
        VoiceModule voice = new VoiceModule(base);
        TrustModule trust = new TrustModule(base);
        ToolModule tools = new ToolModule(base, trust);
        MemoryModule memory = new MemoryModule(base, tools);
        DefenseModule defense = new DefenseModule(base, trust, tools, memory);
        AgentModule agents = new AgentModule(base, trust, tools);
        TaskModule tasks = new TaskModule(base, trust, tools, memory, agents);
        ResponseModule response = new ResponseModule(base, trust, memory, defense, agents);
        // O monitor nasce antes das automações, e pergunta a elas se precisa amostrar.
        AtomicReference<AutomationModule> automationRef = new AtomicReference<>();
        MonitorModule monitor = new MonitorModule(base, trust,
                () -> automationRef.get() != null && automationRef.get().automations().hasConditions());
        AutomationModule automation = new AutomationModule(base, trust, tools, memory, agents, monitor);
        automationRef.set(automation);
        agents.serveTurns(base);
        return new CoreModules(base, voice, trust, tools, memory, defense, agents, tasks, response, monitor, automation);
    }

    void close() {
        voice.close();
        automation.automations().close();
        defense.integrity().close();
        tasks.usage().close();
        defense.hostWatch().close();
        defense.mcp().close();
        base.stopServer();
        memory.distiller().close();
        monitor.close();
        base.bus().close();
        trust.audit().close();
        trust.notifications().close();
        memory.database().close();
    }
}
