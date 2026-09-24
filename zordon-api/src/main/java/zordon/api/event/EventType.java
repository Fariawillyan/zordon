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
package zordon.api.event;

/**
 * Catálogo de eventos (docs/api/zwp-protocol.md §6).
 *
 * <p>Cada entrada carrega o seu tópico: é o que impede um evento de nascer órfão ou
 * de ser publicado em um tópico que ninguém assina para esse tipo de informação.
 * O enum cresce com os marcos — um evento entra aqui quando alguém o publica.
 */
public enum EventType {

    /** Núcleo iniciou. Carrega {@code startId} e {@code version}. */
    CORE_STARTED(Topic.SYSTEM),

    /** Algo digno de atenção fora do fluxo normal. */
    SYSTEM_ALERT(Topic.SYSTEM),

    /** Entrada do usuário aceita, por texto ou por voz. */
    USER_COMMAND(Topic.CHAT),

    /** O modelo está raciocinando; alimenta a linha "pensando…" da interface. */
    AI_THINKING(Topic.CHAT),

    /** Fragmento ou fim da resposta do modelo. */
    AI_RESPONSE(Topic.CHAT),

    /** Falha no turno, com {@code retryable} explícito para a UI decidir. */
    AI_ERROR(Topic.CHAT),

    /** Snapshot do estado da voz a cada mudança (SPEC-006 §8). */
    VOICE_STATE(Topic.VOICE),

    /**
     * Fim de uma escuta: {@code {outcome, confidence, durationMs, reason}} e
     * {@code text} só quando vira comando (SPEC-013 CA-11).
     */
    VOICE_STOPPED(Topic.VOICE),

    /** Uma ativação pela palavra, {@code {score, outcome, bargeIn}}; nunca áudio nem texto (SPEC-013). */
    VOICE_WAKE(Topic.VOICE),

    /** Nível do microfone em dBFS, {@code {rms, peak, bass, mid, treble}}; só no teste ou na escuta (SPEC-009). */
    VOICE_LEVEL(Topic.VOICE),

    /** Estado visual do núcleo, {@code {state}} (SPEC-012). */
    ACTIVITY_STATE(Topic.VOICE),

    /** O que o Zordon vai falar, {@code {text, priority, category}} (SPEC-012). */
    VOICE_NARRATION(Topic.VOICE),

    /** Toda comunicação iniciada pelo Zordon, com os oito campos (SPEC-015, Comunicação §2). */
    SECURITY_NOTIFICATION(Topic.SECURITY),

    /** Zordon em só leitura: {@code {reason, trigger, auto}} (SPEC-015). */
    LOCKDOWN_ENTERED(Topic.SECURITY),

    /** Saída do lockdown: {@code {by}}, sempre {@code user} (SPEC-015). */
    LOCKDOWN_EXITED(Topic.SECURITY),

    /** Uma ferramenta foi chamada: {@code {callId, tool, risk, decision}}, sem argumentos (SPEC-016). */
    TOOL_CALLED(Topic.TOOLS),

    /** O desfecho: {@code {callId, tool, status, durationMs}} (SPEC-016). */
    TOOL_RESULT(Topic.TOOLS),

    /** Um fato gravado na memória: {@code {kind, id, summary}} (SPEC-021). */
    MEMORY_WRITTEN(Topic.MEMORY),

    /** Uma execução de agente começou: {@code {runId, agent, task, parent?}} (SPEC-022). */
    AGENT_STARTED(Topic.AGENTS),

    /** Um passo: {@code {runId, step, note}}. */
    AGENT_PROGRESS(Topic.AGENTS),

    /** O fim: {@code {runId, ok, reason, durationMs, usage, text, parent?}}. */
    AGENT_FINISHED(Topic.AGENTS),

    /** Uma tarefa ou etapa mudou de estado: {@code {taskId, stepId?, state, title?, goal?, reason?}} (SPEC-023). */
    TASK_STATE(Topic.AGENTS),

    /** Um container mudou: {@code {container, image, action, exitCode?, at}} (SPEC-024). */
    CONTAINER_EVENT(Topic.SYSTEM),

    /** Uma automação disparou: {@code {automationId, name, trigger, taskId}} (SPEC-025). */
    AUTOMATION_TRIGGERED(Topic.AUTOMATION),

    /** Terminou: {@code {automationId, ok, summary, taskId}}. */
    AUTOMATION_FINISHED(Topic.AUTOMATION),

    /** Um achado da defesa: {@code {findingId, severity, detector, subject, title, rationale}} (SPEC-026). */
    SECURITY_FINDING(Topic.SECURITY),

    /** A defesa agiu: {@code {eventId, subject, executed, outcome, reversible}} (SPEC-027). */
    SECURITY_ACTION_TAKEN(Topic.SECURITY),

    /** Disjuntor aberto: {@code {subject, reason, findingId}}. */
    CIRCUIT_BREAKER_OPENED(Topic.SECURITY),

    /** Disjuntor liberado pelo usuário: {@code {subject, by, state}}. */
    CIRCUIT_BREAKER_CLOSED(Topic.SECURITY);

    private final String topic;

    EventType(String topic) {
        this.topic = topic;
    }

    public String topic() {
        return topic;
    }
}
