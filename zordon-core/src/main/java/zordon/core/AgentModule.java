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

import java.util.List;
import zordon.api.security.Severity;
import zordon.core.agents.AgentProfile;
import zordon.core.agents.AgentRegistry;
import zordon.core.agents.AgentRunner;
import zordon.core.agents.AgentService;
import zordon.core.agents.DelegateTool;
import zordon.core.agents.TurnScopes;
import zordon.core.notify.NotificationCenter;
import zordon.core.tools.ModelToolCaller;
import zordon.core.tools.ProcessTools;

/** Agentes como configuração (SPEC-022): o registro, o runner, a delegação e o aviso do disjuntor. */
final class AgentModule {

    private final NotificationCenter notifications;
    private final ModelToolCaller modelTools;
    private final AgentRegistry agents;
    private final AgentRunner runner;
    private final AgentService runs;

    AgentModule(CoreBase base, TrustModule trust, ToolModule tools) {
        this.notifications = trust.notifications();
        TurnScopes scopes = new TurnScopes();
        this.modelTools = new ModelToolCaller(tools.tools(), scopes);
        this.agents = new AgentRegistry(base.config().home().resolve("agents"));
        this.runner = new AgentRunner(base.providers(), base.prompts(), modelTools, base.bus())
                .onSuspended(this::suspended);
        tools.tools().register(new DelegateTool(agents, scopes, runner, System::nanoTime))
                .register(ProcessTools.dockerPs(tools.userHome(), trust.runner()))
                .register(ProcessTools.dockerLogs(tools.userHome(), trust.runner()));
        this.runs = new AgentService(agents, runner, System::nanoTime);
    }

    AgentRegistry registry() {
        return agents;
    }

    AgentRunner runner() {
        return runner;
    }

    AgentService runs() {
        return runs;
    }

    /** O que o turno chama de volta: só depois que todo o resto existe. */
    void serveTurns(CoreBase base) {
        base.turns().hooks().onAgents(agents);
        base.turns().hooks().onSuspended(this::suspended);
        base.router().knowAgents(id -> agents.find(id).isPresent());
        base.turns().hooks().onToolCalls(modelTools);
    }

    /** O disjuntor de uma execução abriu: aviso HIGH, com os sinais (SPEC-022 CA-5). */
    private void suspended(AgentProfile agent, String reason) {
        notifications.publish(notifications.message(new NotificationCenter.MessageFields(
                Severity.HIGH, "AI_DEFENSE",
                "O agente " + agent.id() + " foi suspenso",
                "A execução do agente " + agent.id() + " parou: " + reason + ".",
                "Negações seguidas, tentativa acima do teto ou repetição sem progresso são o comportamento de um agente"
                        + " desviado do objetivo, por exemplo por conteúdo malicioso que ele leu.",
                "disjuntor da execução do agente (SPEC-022)",
                "A execução foi encerrada; nenhuma ação a mais foi feita.",
                "agente " + agent.id(), true,
                "Encerrado. Um pedido novo começa do zero.",
                List.of("Ver o que o agente fez no Live Trace", "Pedir de novo"))));
    }
}
