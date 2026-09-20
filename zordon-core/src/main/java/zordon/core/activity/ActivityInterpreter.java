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
package zordon.core.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import zordon.api.event.EventEnvelope;
import zordon.api.trace.Spec;

/**
 * Transforma eventos técnicos em etapas: o estado visual e, quando merece, uma
 * frase (SPEC-012 §7). Puro: recebe eventos e a hora, devolve o que mostrar e o
 * que dizer.
 *
 * <p>As frases saem de modelos por tipo de evento. O texto que o modelo produz ao
 * raciocinar, e os fragmentos de uma resposta em streaming, nunca viram fala
 * (ADR-0029). A resposta final de um turno de voz é falada porque é a resposta
 * ao usuário, não um pensamento.
 */
@Spec("SPEC-012")
public final class ActivityInterpreter {

    public sealed interface Output permits Show, Say {}

    /** Mudou o estado visual. */
    public record Show(ActivityState state) implements Output {}

    /** Candidata a fala; o narrador decide se e quando. */
    public record Say(Narration narration) implements Output {}

    static final Duration SLOW_TURN = Duration.ofSeconds(8);
    static final Duration DONE_HOLD = Duration.ofMillis(1500);
    static final Duration ERROR_HOLD = Duration.ofSeconds(3);

    static final String STILL_WORKING = "Ainda estou trabalhando nisso.";
    static final String NO_PROVIDER = "O provedor de IA não está configurado.";
    static final String NO_CREDIT = "O provedor de IA recusou por falta de crédito.";
    static final String COULD_NOT_ANSWER = "Não consegui responder agora.";
    static final String MIC_OK = "Microfone funcionando.";
    static final String MIC_LOW = "O sinal do microfone está baixo.";
    static final String MIC_SILENT = "Não ouvi nada no microfone.";
    static final String MIC_FAILED = "Não consegui testar o microfone.";
    /** Transcrição com confiança baixa não age (SPEC-013 CA-10). */
    static final String MISHEARD = "Não entendi.";
    static final String PAUSED = "Zordon pausado. Só leitura até você retomar na tela.";
    static final String RESUMED = "Zordon retomado.";
    /** Plano durável (SPEC-023): "concluída" só depois do último veredito. */
    static final String TASK_DONE = "Tarefa concluída.";
    static final String CHECK_RESULT = "Preciso que você confira o resultado.";

    private record Turn(boolean voice, Instant started, boolean slowSaid) {}

    private final Map<String, Turn> turns = new HashMap<>();
    private ActivityState state = ActivityState.IDLE;
    private Instant settledAt;
    private boolean listening;
    private boolean speaking;
    private Object lastTest;

    public ActivityState state() {
        return state;
    }

    public List<Output> accept(EventEnvelope event) {
        List<Output> out = new ArrayList<>();
        Map<String, Object> payload = event.payload();
        switch (event.type()) {
            case USER_COMMAND -> {
                turns.put(text(payload.get("turnId")),
                        new Turn("voice".equals(payload.get("source")), event.ts(), false));
                show(out, ActivityState.UNDERSTANDING);
            }
            case AI_THINKING -> {
                if (turns.containsKey(text(payload.get("turnId")))) {
                    show(out, ActivityState.PLANNING);
                }
            }
            case AI_RESPONSE -> {
                // Fragmento de streaming: nada a dizer nem a mudar.
                if (Boolean.TRUE.equals(payload.get("done"))) {
                    Turn turn = turns.remove(text(payload.get("turnId")));
                    settle(out, ActivityState.DONE, event.ts());
                    // A voz diz o resumo, sem Markdown, e aponta para a tela
                    // (Comunicação §3). Ler a resposta inteira, com asteriscos,
                    // era o que acontecia até 2026-09-20 (SPEC-034).
                    String spoken = SpokenAnswer.of(text(payload.get("text")));
                    if (turn != null && turn.voice() && !spoken.isBlank()) {
                        out.add(new Say(new Narration(spoken, Narration.Priority.HIGH, "resultado")));
                    }
                }
            }
            case AI_ERROR -> {
                Turn turn = turns.remove(text(payload.get("turnId")));
                settle(out, ActivityState.ERROR, event.ts());
                if (turn != null && turn.voice()) {
                    out.add(new Say(new Narration(errorPhrase(text(payload.get("kind"))),
                            Narration.Priority.HIGH, "erro")));
                }
            }
            case VOICE_STATE -> voice(out, payload);
            case SECURITY_NOTIFICATION -> {
                // A voz diz o resumo e aponta para a tela; nunca lê a mensagem inteira (Comunicação §3).
                if ("critical".equals(payload.get("severity"))) {
                    show(out, ActivityState.ATTENTION);
                    out.add(new Say(new Narration(text(payload.get("title")) + ". Os detalhes estão na tela.",
                            Narration.Priority.AUTHORIZATION, "alerta")));
                }
            }
            case TOOL_CALLED -> {
                if ("allow".equals(payload.get("decision"))) {
                    show(out, ActivityState.EXECUTING);
                }
            }
            case TASK_STATE -> task(out, payload, event.ts());
            case LOCKDOWN_ENTERED -> out.add(new Say(new Narration(PAUSED, Narration.Priority.HIGH, "resultado")));
            case LOCKDOWN_EXITED -> out.add(new Say(new Narration(RESUMED, Narration.Priority.HIGH, "resultado")));
            case VOICE_STOPPED -> {
                if ("low_confidence".equals(payload.get("outcome"))) {
                    out.add(new Say(new Narration(MISHEARD, Narration.Priority.HIGH, "resultado")));
                }
            }
            // Técnicos ou próprios: ficam no trace, não mudam o que o usuário vê ou ouve.
            default -> { }
        }
        return out;
    }

    /** A voz de um plano: o título de cada etapa, e o fim só com veredito (Avaliação §5). */
    private void task(List<Output> out, Map<String, Object> payload, Instant ts) {
        String state = text(payload.get("state"));
        boolean step = payload.get("stepId") != null;
        if (step && "running".equals(state)) {
            show(out, ActivityState.AGENTS);
            out.add(new Say(new Narration(text(payload.get("title")), Narration.Priority.NORMAL, "etapa")));
        } else if (!step && "done".equals(state)) {
            settle(out, ActivityState.DONE, ts);
            out.add(new Say(new Narration(TASK_DONE, Narration.Priority.HIGH, "resultado")));
        } else if (!step && "waiting_human".equals(state)) {
            show(out, ActivityState.ATTENTION);
            out.add(new Say(new Narration(CHECK_RESULT, Narration.Priority.HIGH, "resultado")));
        } else if (!step && "failed".equals(state)) {
            settle(out, ActivityState.ERROR, ts);
            out.add(new Say(new Narration("A tarefa não foi concluída.", Narration.Priority.HIGH, "erro")));
        } else if (!step && "blocked".equals(state) && "o núcleo reiniciou".equals(payload.get("reason"))) {
            out.add(new Say(new Narration("Uma tarefa foi interrompida: " + text(payload.get("goal"))
                    + ". Quer que eu continue? Está na tela.", Narration.Priority.HIGH, "resultado")));
        }
    }

    /** O que o tempo muda: turno demorado, fim do "concluído" e do "erro". */
    public List<Output> tick(Instant now) {
        List<Output> out = new ArrayList<>();
        turns.replaceAll((id, turn) -> {
            if (turn.voice() && !turn.slowSaid() && !now.isBefore(turn.started().plus(SLOW_TURN))) {
                out.add(new Say(new Narration(STILL_WORKING, Narration.Priority.NORMAL, "etapa")));
                return new Turn(true, turn.started(), true);
            }
            return turn;
        });
        if (settledAt != null) {
            Duration hold = state == ActivityState.ERROR ? ERROR_HOLD : DONE_HOLD;
            if (!now.isBefore(settledAt.plus(hold))) {
                settledAt = null;
                show(out, resting());
            }
        }
        return out;
    }

    private void voice(List<Output> out, Map<String, Object> snapshot) {
        String activity = text(snapshot.get("activity"));
        boolean testing = snapshot.get("test") != null;
        listening = testing || "listening".equals(activity);
        speaking = "speaking".equals(activity);
        if (listening) {
            show(out, ActivityState.LISTENING);
        } else if ("thinking".equals(activity)) {
            show(out, ActivityState.UNDERSTANDING);
        } else if (speaking) {
            show(out, ActivityState.SPEAKING);
        } else if (settledAt == null && turns.isEmpty()) {
            show(out, ActivityState.IDLE);
        }
        Object test = snapshot.get("lastTest");
        if (test instanceof Map<?, ?> result && !Objects.equals(test, lastTest)) {
            lastTest = test;
            out.add(new Say(new Narration(micPhrase(text(result.get("verdict"))), Narration.Priority.NORMAL,
                    "resultado")));
        }
    }

    private void settle(List<Output> out, ActivityState result, Instant at) {
        settledAt = at;
        show(out, result);
    }

    private ActivityState resting() {
        if (listening) {
            return ActivityState.LISTENING;
        }
        if (speaking) {
            return ActivityState.SPEAKING;
        }
        return turns.isEmpty() ? ActivityState.IDLE : ActivityState.PLANNING;
    }

    private void show(List<Output> out, ActivityState next) {
        if (next != state) {
            state = next;
            out.add(new Show(next));
        }
    }

    static String errorPhrase(String kind) {
        return switch (kind) {
            case "NO_CREDENTIALS" -> NO_PROVIDER;
            case "QUOTA_EXHAUSTED" -> NO_CREDIT;
            default -> COULD_NOT_ANSWER;
        };
    }

    static String micPhrase(String verdict) {
        return switch (verdict) {
            case "ok" -> MIC_OK;
            case "low" -> MIC_LOW;
            case "silent" -> MIC_SILENT;
            default -> MIC_FAILED;
        };
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
