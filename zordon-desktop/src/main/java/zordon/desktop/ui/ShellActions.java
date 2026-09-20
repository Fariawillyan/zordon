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

import zordon.desktop.shell.ComposerTarget;

/**
 * O que o shell pede a quem fala com o núcleo. A tela nunca chama o protocolo
 * direto: é isso que permite exercitá-la sem núcleo nenhum.
 */
public interface ShellActions {

    void send(String text, ComposerTarget target);

    void newConversation();

    void cancelTurn(String turnId);

    void refreshDiagnostics();

    /** {@code voice.setMode}; {@code mode} é {@code off}, {@code wake}, {@code push} ou {@code open}. */
    void setVoiceMode(String mode);

    void loadVoiceDevices();

    void selectVoiceDevice(String deviceId);

    /** {@code voice.testMicrophone} por 5 s (SPEC-009). */
    void testMicrophone();

    /** Começa uma escuta pedida pelo usuário (SPEC-011). */
    void startListening();

    /** Encerra a escuta em curso. */
    void stopListening();

    /** O usuário viu o aviso: {@code notify.acknowledge} (SPEC-015). */
    default void acknowledge(String messageId) {}

    /** "Pausar Zordon": só leitura até retomar pela tela (SPEC-015). */
    default void pauseZordon() {}

    /** Sai do só leitura. Só a tela faz isso. */
    default void resumeZordon() {}

    /** {@code security.quarantine.list} (SPEC-017). */
    default void loadQuarantine() {}

    /** Devolve um item da quarentena, pelo caminho mediado. */
    default void restoreQuarantine(String vaultId) {}

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

    /** {@code security.findings} (SPEC-026). */
    default void loadFindings() {}

    /** Confirma que leu um achado. Só a tela faz isso. */
    default void acknowledgeFinding(String findingId) {}

    /** Libera um disjuntor: {@code supervised} ou {@code closed}. Só a tela faz isso. */
    default void releaseBreaker(String subject, String mode) {}

    /** {@code agent.list} (SPEC-022). */
    default void loadAgents() {}

    /** {@code memory.facts} (SPEC-021). */
    default void loadMemory() {}

    /** Esquece um fato de verdade. Só a tela faz isso. */
    default void forgetFact(String factId) {}
}
