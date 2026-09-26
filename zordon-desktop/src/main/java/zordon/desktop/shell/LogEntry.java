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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;

/**
 * Uma linha do log de atividades.
 *
 * <p>O log não é um recurso separado: é uma projeção do mesmo fluxo de eventos que
 * alimenta o chat ([UI §8](../../../../../docs/specs/ui/design.md#8-log-de-atividades)).
 */
public record LogEntry(String time, long seq, String topic, String type, String detail) {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final Map<EventType, Function<Map<String, Object>, String>> SUMMARIES = summaries();

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
        return SUMMARIES.get(event.type()).apply(event.payload());
    }

    /**
     * Um resumo por tipo de evento.
     *
     * <p>Era um {@code switch} exaustivo, e o compilador conferia que nenhum tipo
     * ficava de fora. Virou tabela por causa do limite de complexidade
     * ([padrões §5](../../../../../docs/process/code-standards.md#5-complexidade)):
     * cada {@code case} é um caminho, e eram 29.
     *
     * <p>A garantia que o compilador dava passa a ser conferida aqui, na carga da
     * classe: um tipo de evento novo sem resumo derruba a janela ao abrir, com o
     * nome do tipo no erro — e não some em silêncio na tela de Logs, que é onde
     * ninguém notaria.
     */
    private static Map<EventType, Function<Map<String, Object>, String>> summaries() {
        Map<EventType, Function<Map<String, Object>, String>> out = new EnumMap<>(EventType.class);
        out.put(EventType.CORE_STARTED, payload -> "núcleo " + payload.getOrDefault("version", "?")
                + " · " + payload.getOrDefault("startId", "?"));
        out.put(EventType.USER_COMMAND, payload -> "\"" + payload.getOrDefault("text", "") + "\""
                + " · " + payload.getOrDefault("source", "text"));
        out.put(EventType.AI_THINKING, payload -> thinking(payload));
        out.put(EventType.AI_RESPONSE, payload -> Boolean.TRUE.equals(payload.get("done"))
                ? finished(payload)
                : "fragmento");
        out.put(EventType.AI_ERROR, payload -> payload.getOrDefault("kind", "erro") + " · " + payload.getOrDefault("message", ""));
        out.put(EventType.SYSTEM_ALERT, payload -> String.valueOf(payload.getOrDefault("message", "")));
        out.put(EventType.VOICE_STATE, payload -> "modo " + payload.getOrDefault("mode", "?")
                + " · em vigor " + payload.getOrDefault("effective", "?")
                + " · microfone " + (payload.get("capture") instanceof Map<?, ?> capture ? capture.get("state") : "?")
                + (payload.get("reason") instanceof String reason ? " — " + reason : ""));
        out.put(EventType.VOICE_LEVEL, payload -> "nível " + payload.getOrDefault("rms", "?") + " dBFS · pico "
                + payload.getOrDefault("peak", "?") + " dBFS");
        out.put(EventType.ACTIVITY_STATE, payload -> "estado " + payload.getOrDefault("state", "?"));
        out.put(EventType.VOICE_NARRATION, payload -> "fala (" + payload.getOrDefault("priority", "?") + "): \""
                + payload.getOrDefault("text", "") + "\"");
        out.put(EventType.VOICE_STOPPED, payload -> (payload.get("text") instanceof String text ? "\"" + text + "\" · " : "")
                + payload.getOrDefault("outcome", "fim") + " · confiança " + payload.getOrDefault("confidence", "?"));
        out.put(EventType.SECURITY_NOTIFICATION, payload -> payload.getOrDefault("severity", "?") + " · "
                + payload.getOrDefault("title", "") + " · " + payload.getOrDefault("actionTaken", ""));
        out.put(EventType.LOCKDOWN_ENTERED, payload -> "Zordon pausado (só leitura) · " + payload.getOrDefault("reason", ""));
        out.put(EventType.LOCKDOWN_EXITED, payload -> "Zordon retomado · por " + payload.getOrDefault("by", "?"));
        out.put(EventType.OPPRESSOR_ENTERED, payload -> "OPPRESSOR MODE ativo · toda ação liberada sem avaliação · por "
                + payload.getOrDefault("trigger", "?"));
        out.put(EventType.OPPRESSOR_EXITED, payload -> "OPPRESSOR MODE encerrado · motor de permissão de volta · por "
                + payload.getOrDefault("by", "?"));
        out.put(EventType.OPPRESSOR_PROMPT, payload -> "OPPRESSOR MODE pedido por voz · a janela vai pedir a senha");
        out.put(EventType.TOOL_CALLED, payload -> "ferramenta " + payload.getOrDefault("tool", "?") + " · "
                + payload.getOrDefault("risk", "?") + " · " + payload.getOrDefault("decision", "?"));
        out.put(EventType.TOOL_RESULT, payload -> "ferramenta " + payload.getOrDefault("tool", "?") + " · "
                + payload.getOrDefault("status", "?") + " · " + payload.getOrDefault("durationMs", "?") + " ms");
        out.put(EventType.VOICE_WAKE, payload -> "palavra \"Zordon\" · " + payload.getOrDefault("score", "?") + " · "
                + payload.getOrDefault("outcome", "?") + (Boolean.TRUE.equals(payload.get("bargeIn"))
                        ? " · interrompeu a fala" : ""));
        out.put(EventType.MEMORY_WRITTEN, payload -> "memória · " + payload.getOrDefault("kind", "?") + " · "
                + payload.getOrDefault("summary", ""));
        out.put(EventType.AGENT_STARTED, payload -> "agente " + payload.getOrDefault("agent", "?") + " começou"
                + (payload.get("parent") instanceof String parent ? " (delegado por " + parent + ")" : "")
                + " · " + payload.getOrDefault("task", ""));
        out.put(EventType.AGENT_PROGRESS, payload -> "agente · passo " + payload.getOrDefault("step", "?") + " · "
                + payload.getOrDefault("note", ""));
        out.put(EventType.AGENT_FINISHED, payload -> "agente " + payload.getOrDefault("agent", "?") + " terminou · "
                + payload.getOrDefault("reason", "?") + " · " + payload.getOrDefault("durationMs", "?") + " ms");
        out.put(EventType.SECURITY_ACTION_TAKEN, payload -> "defesa · " + payload.getOrDefault("executed", "?") + " em "
                + payload.getOrDefault("subject", "?") + " · " + payload.getOrDefault("outcome", "?"));
        out.put(EventType.CIRCUIT_BREAKER_OPENED, payload -> "disjuntor aberto · " + payload.getOrDefault("subject", "?") + " · "
                + payload.getOrDefault("reason", ""));
        out.put(EventType.CIRCUIT_BREAKER_CLOSED, payload -> "disjuntor liberado · " + payload.getOrDefault("subject", "?") + " · "
                + payload.getOrDefault("state", "?"));
        out.put(EventType.SECURITY_FINDING, payload -> "achado " + payload.getOrDefault("severity", "?") + " · "
                + payload.getOrDefault("detector", "?") + " · " + payload.getOrDefault("title", ""));
        out.put(EventType.CONTAINER_EVENT, payload -> "container " + payload.getOrDefault("container", "?") + " · "
                + payload.getOrDefault("action", "?")
                + (payload.get("exitCode") != null ? " (código " + payload.get("exitCode") + ")" : ""));
        out.put(EventType.AUTOMATION_TRIGGERED, payload -> "automação " + payload.getOrDefault("name", "?")
                + " · " + payload.getOrDefault("trigger", ""));
        out.put(EventType.AUTOMATION_FINISHED, payload -> "automação " + payload.getOrDefault("automationId", "?")
                + " · " + (Boolean.TRUE.equals(payload.get("ok")) ? "concluída" : "falhou")
                + " · " + payload.getOrDefault("summary", ""));
        out.put(EventType.TASK_STATE, payload -> "tarefa " + payload.getOrDefault("taskId", "?")
                + (payload.get("stepId") instanceof String step ? "/" + step : "") + " · "
                + payload.getOrDefault("state", "?")
                + (payload.get("title") instanceof String title ? " · " + title : "")
                + (payload.get("reason") instanceof String reason ? " · " + reason : ""));
        Set<EventType> semResumo = EnumSet.allOf(EventType.class);
        semResumo.removeAll(out.keySet());
        if (!semResumo.isEmpty()) {
            throw new IllegalStateException("tipo de evento sem resumo na tela de Logs: " + semResumo);
        }
        return out;
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
