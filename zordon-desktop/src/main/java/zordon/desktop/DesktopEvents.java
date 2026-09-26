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
package zordon.desktop;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.desktop.shell.TurnSummary;
import zordon.desktop.ui.ZordonShell;

/** Os eventos do núcleo aplicados à janela: o estado, o log, a conversa e as recargas. */
final class DesktopEvents {

    private final DesktopContext context;
    private final DesktopActions actions;

    DesktopEvents(DesktopContext context, DesktopActions actions) {
        this.context = context;
        this.actions = actions;
    }

    /** Aplica um lote de eventos. Roda na thread da interface, uma vez a cada 33 ms. */
    void apply(List<EventEnvelope> batch) {
        for (EventEnvelope event : batch) {
            context.state().accept(event);
            // 20 níveis por segundo afogariam o log; o medidor já os mostra.
            if (event.type() != EventType.VOICE_LEVEL) {
                context.shell().logs().append(event);
            }
            chat(event.type(), event.payload());
            react(event.type(), event.payload());
        }
    }

    /** O que o evento muda na conversa. */
    private void chat(EventType type, Map<String, Object> payload) {
        ZordonShell shell = context.shell();
        String turnId = String.valueOf(payload.getOrDefault("turnId", ""));
        switch (type) {
            case AI_THINKING -> thinking(shell, payload);
            case AI_RESPONSE -> {
                if (Boolean.TRUE.equals(payload.get("done"))) {
                    shell.chatComplete(turnId, String.valueOf(payload.getOrDefault("text", "")),
                            TurnSummary.fromResponse(payload).map(TurnSummary::footer).orElse(""));
                } else if (payload.get("delta") instanceof String delta) {
                    shell.chatDelta(turnId, delta);
                }
            }
            case AI_ERROR -> shell.chatError(payload.get("message")
                    + (Boolean.TRUE.equals(payload.get("retryable")) ? "  ·  dá para tentar de novo" : ""));
            default -> { }
        }
    }

    private static void thinking(ZordonShell shell, Map<String, Object> payload) {
        if (payload.get("notice") instanceof String notice) {
            shell.chatNotice(notice.substring(0, 1).toUpperCase(Locale.ROOT) + notice.substring(1) + ".");
        }
        // A troca de quem responde é dita enquanto acontece, não depois.
        if (payload.get("fallbackFrom") instanceof String from) {
            shell.chatNotice("%s falhou (%s). Quem vai responder é a reserva: %s.".formatted(
                    from, payload.getOrDefault("reason", "sem motivo"), payload.getOrDefault("provider", "?")));
        }
    }

    /** O que o evento pede à janela: um diálogo ou recarregar uma tela. */
    private void react(EventType type, Map<String, Object> payload) {
        switch (type) {
            // A voz pediu o modo: a janela vem para a frente e pede a senha.
            case OPPRESSOR_PROMPT -> actions.security().promptOppressor();
            case SECURITY_NOTIFICATION -> {
                actions.security().notified(payload);
                if (String.valueOf(payload.get("detectedBy")).startsWith("automation:")) {
                    actions.data().loadAutomations();
                }
            }
            case AUTOMATION_TRIGGERED, AUTOMATION_FINISHED -> actions.data().loadAutomations();
            case MEMORY_WRITTEN -> actions.memory().loadMemory();
            case SECURITY_FINDING -> actions.security().loadFindings();
            case TASK_STATE -> actions.data().loadTasks();
            case SYSTEM_ALERT -> {
                if ("mcp".equals(payload.get("source"))) {
                    actions.data().loadMcp();
                }
            }
            default -> { }
        }
    }
}
