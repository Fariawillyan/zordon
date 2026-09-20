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
package zordon.desktop.shell;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import zordon.api.event.EventEnvelope;

/**
 * Uma linha do log de atividades.
 *
 * <p>O log não é um recurso separado: é uma projeção do mesmo fluxo de eventos que
 * alimenta o chat ([UI §8](../../../../../docs/specs/ui/design.md#8-log-de-atividades)).
 */
public record LogEntry(String time, long seq, String topic, String type, String detail) {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public static LogEntry of(EventEnvelope event) {
        return new LogEntry(
                CLOCK.format(event.ts()),
                event.seq(),
                event.topic(),
                event.type().name(),
                summarize(event));
    }

    /**
     * Resumo factual, nunca o despejo do payload: um fragmento de streaming por
     * linha tornaria a tela ilegível justamente quando ela é mais necessária.
     */
    private static String summarize(EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        return switch (event.type()) {
            case CORE_STARTED -> "núcleo " + payload.getOrDefault("version", "?")
                    + " · " + payload.getOrDefault("startId", "?");
            case USER_COMMAND -> "\"" + payload.getOrDefault("text", "") + "\""
                    + " · " + payload.getOrDefault("source", "text");
            case AI_THINKING -> thinking(payload);
            case AI_RESPONSE -> Boolean.TRUE.equals(payload.get("done"))
                    ? finished(payload)
                    : "fragmento";
            case AI_ERROR -> payload.getOrDefault("kind", "erro") + " · " + payload.getOrDefault("message", "");
            case SYSTEM_ALERT -> String.valueOf(payload.getOrDefault("message", ""));
            case VOICE_STATE -> "modo " + payload.getOrDefault("mode", "?")
                    + " · em vigor " + payload.getOrDefault("effective", "?")
                    + " · microfone " + (payload.get("capture") instanceof Map<?, ?> capture ? capture.get("state") : "?")
                    + (payload.get("reason") instanceof String reason ? " — " + reason : "");
            case VOICE_LEVEL -> "nível " + payload.getOrDefault("rms", "?") + " dBFS · pico "
                    + payload.getOrDefault("peak", "?") + " dBFS";
            case ACTIVITY_STATE -> "estado " + payload.getOrDefault("state", "?");
            case VOICE_NARRATION -> "fala (" + payload.getOrDefault("priority", "?") + "): \""
                    + payload.getOrDefault("text", "") + "\"";
            case VOICE_STOPPED -> (payload.get("text") instanceof String text ? "\"" + text + "\" · " : "")
                    + payload.getOrDefault("outcome", "fim") + " · confiança " + payload.getOrDefault("confidence", "?");
            case SECURITY_NOTIFICATION -> payload.getOrDefault("severity", "?") + " · "
                    + payload.getOrDefault("title", "") + " · " + payload.getOrDefault("actionTaken", "");
            case LOCKDOWN_ENTERED -> "Zordon pausado (só leitura) · " + payload.getOrDefault("reason", "");
            case LOCKDOWN_EXITED -> "Zordon retomado · por " + payload.getOrDefault("by", "?");
            case TOOL_CALLED -> "ferramenta " + payload.getOrDefault("tool", "?") + " · "
                    + payload.getOrDefault("risk", "?") + " · " + payload.getOrDefault("decision", "?");
            case TOOL_RESULT -> "ferramenta " + payload.getOrDefault("tool", "?") + " · "
                    + payload.getOrDefault("status", "?") + " · " + payload.getOrDefault("durationMs", "?") + " ms";
            case VOICE_WAKE -> "palavra \"Zordon\" · " + payload.getOrDefault("score", "?") + " · "
                    + payload.getOrDefault("outcome", "?") + (Boolean.TRUE.equals(payload.get("bargeIn"))
                            ? " · interrompeu a fala" : "");
            case MEMORY_WRITTEN -> "memória · " + payload.getOrDefault("kind", "?") + " · "
                    + payload.getOrDefault("summary", "");
            case AGENT_STARTED -> "agente " + payload.getOrDefault("agent", "?") + " começou"
                    + (payload.get("parent") instanceof String parent ? " (delegado por " + parent + ")" : "")
                    + " · " + payload.getOrDefault("task", "");
            case AGENT_PROGRESS -> "agente · passo " + payload.getOrDefault("step", "?") + " · "
                    + payload.getOrDefault("note", "");
            case AGENT_FINISHED -> "agente " + payload.getOrDefault("agent", "?") + " terminou · "
                    + payload.getOrDefault("reason", "?") + " · " + payload.getOrDefault("durationMs", "?") + " ms";
            case SECURITY_ACTION_TAKEN -> "defesa · " + payload.getOrDefault("executed", "?") + " em "
                    + payload.getOrDefault("subject", "?") + " · " + payload.getOrDefault("outcome", "?");
            case CIRCUIT_BREAKER_OPENED -> "disjuntor aberto · " + payload.getOrDefault("subject", "?") + " · "
                    + payload.getOrDefault("reason", "");
            case CIRCUIT_BREAKER_CLOSED -> "disjuntor liberado · " + payload.getOrDefault("subject", "?") + " · "
                    + payload.getOrDefault("state", "?");
            case SECURITY_FINDING -> "achado " + payload.getOrDefault("severity", "?") + " · "
                    + payload.getOrDefault("detector", "?") + " · " + payload.getOrDefault("title", "");
            case CONTAINER_EVENT -> "container " + payload.getOrDefault("container", "?") + " · "
                    + payload.getOrDefault("action", "?")
                    + (payload.get("exitCode") != null ? " (código " + payload.get("exitCode") + ")" : "");
            case AUTOMATION_TRIGGERED -> "automação " + payload.getOrDefault("name", "?")
                    + " · " + payload.getOrDefault("trigger", "");
            case AUTOMATION_FINISHED -> "automação " + payload.getOrDefault("automationId", "?")
                    + " · " + (Boolean.TRUE.equals(payload.get("ok")) ? "concluída" : "falhou")
                    + " · " + payload.getOrDefault("summary", "");
            case TASK_STATE -> "tarefa " + payload.getOrDefault("taskId", "?")
                    + (payload.get("stepId") instanceof String step ? "/" + step : "") + " · "
                    + payload.getOrDefault("state", "?")
                    + (payload.get("title") instanceof String title ? " · " + title : "")
                    + (payload.get("reason") instanceof String reason ? " · " + reason : "");
        };
    }

    private static String thinking(Map<String, Object> payload) {
        if (!payload.containsKey("model")) {
            return "raciocinando";
        }
        String base = payload.getOrDefault("provider", "?") + " · " + payload.get("model")
                + " · agente " + payload.getOrDefault("agentId", "?");
        return payload.get("fallbackFrom") instanceof String from
                ? "reserva " + base + " · no lugar de " + from + ": " + payload.getOrDefault("reason", "")
                : base;
    }

    private static String value(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "0" : value.toString();
    }

    private static String finished(Map<String, Object> payload) {
        if (payload.get("route") instanceof String route) {
            return "resposta por rota rápida (" + route + ")";
        }
        String tokens = payload.get("usage") instanceof Map<?, ?> usage
                ? value(usage, "inputTokens") + " entrada / " + value(usage, "outputTokens") + " saída"
                        + (Boolean.TRUE.equals(usage.get("estimated")) ? " (estimado)" : "")
                : "—";
        String who = payload.get("provider") instanceof String provider ? provider + " · " : "";
        String fallback = payload.get("fallbackFrom") instanceof String from ? " · reserva no lugar de " + from : "";
        return "resposta completa · " + who + tokens + " tok · US$ " + payload.getOrDefault("costUsd", "0")
                + " · " + payload.getOrDefault("latencyMs", "?") + " ms" + fallback;
    }
}
