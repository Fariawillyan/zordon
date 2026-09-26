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

/** O trabalho: servidores MCP, tarefas, automações, agentes, ferramentas, uso e sistema. */
public interface ShellDataActions {

    /** Nada: para quem só mostra a tela, como os testes. */
    ShellDataActions NONE = new ShellDataActions() { };

    /** {@code mcp.servers} (SPEC-020). */
    default void loadMcp() {}

    /** Aceita a superfície nova de um servidor MCP. Só a tela faz isso. */
    default void approveMcp(String server) {}

    /** {@code task.list} e as etapas de cada tarefa (SPEC-023). */
    default void loadTasks() {}

    /** Confirma ou reprova uma etapa que espera o usuário. Só a tela faz isso. */
    default void confirmStep(String taskId, String stepId, boolean pass) {}

    /** Continua uma tarefa bloqueada. Só a tela faz isso. */
    default void resumeTask(String taskId) {}

    default void loadAutomations() {}

    default void approveAutomation(String proposalId) {}

    default void rejectAutomation(String proposalId) {}

    default void enableAutomation(String id, boolean enabled) {}

    default void runAutomation(String id) {}

    /** {@code agent.list} (SPEC-022). */
    default void loadAgents() {}

    /** {@code tools.list} (SPEC-019): o que o modelo pode pedir. */
    default void loadSkills() {}

    /** {@code usage.summary} (SPEC-029). */
    default void loadUsage() {}

    /** {@code system.metrics} e {@code monitor.status} (SPEC-024). */
    default void loadSystem() {}
}
