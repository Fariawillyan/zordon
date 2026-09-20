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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.trace.AcceptanceCriteria;

class ActivityInterpreterTest {

    private static final Instant T0 = Instant.parse("2026-09-18T18:00:00Z");

    private final ActivityInterpreter interpreter = new ActivityInterpreter();
    private long seq;

    @AcceptanceCriteria("SPEC-012/CA-1")
    @Test
    void cadaEventoDaTabelaViraAEtapaEOEstado() {
        assertThat(states(event(EventType.VOICE_STATE, Map.of("activity", "listening")))).containsExactly("LISTENING");
        // A escuta acaba (o motor transcreve) e o comando chega.
        assertThat(states(event(EventType.VOICE_STATE, Map.of("activity", "thinking")))).containsExactly("UNDERSTANDING");
        interpreter.accept(event(EventType.VOICE_STATE, Map.of("activity", "idle")));
        assertThat(states(event(EventType.USER_COMMAND, Map.of("turnId", "t1", "source", "voice", "text", "que horas são"))))
                .containsExactly("UNDERSTANDING");
        assertThat(states(event(EventType.AI_THINKING, Map.of("turnId", "t1")))).containsExactly("PLANNING");
        assertThat(states(event(EventType.AI_RESPONSE, Map.of("turnId", "t1", "done", true, "text", "São 15h40."))))
                .containsExactly("DONE");
        assertThat(interpreter.tick(T0.plus(Duration.ofSeconds(1)))).isEmpty();
        assertThat(interpreter.tick(T0.plus(Duration.ofSeconds(2)))).containsExactly(
                new ActivityInterpreter.Show(ActivityState.IDLE));

        interpreter.accept(event(EventType.USER_COMMAND, Map.of("turnId", "t2", "source", "text", "text", "oi")));
        assertThat(states(event(EventType.AI_ERROR, Map.of("turnId", "t2", "kind", "TIMEOUT")))).containsExactly("ERROR");
        assertThat(states(event(EventType.VOICE_STATE, Map.of("activity", "idle", "test", Map.of("until", "x")))))
                .containsExactly("LISTENING");
    }

    @AcceptanceCriteria("SPEC-012/CA-1")
    @Test
    void eventosTecnicosEOsProprioNaoMudamNada() {
        for (EventType type : List.of(EventType.SYSTEM_ALERT, EventType.VOICE_LEVEL, EventType.ACTIVITY_STATE,
                EventType.VOICE_NARRATION, EventType.CORE_STARTED)) {
            assertThat(interpreter.accept(event(type, Map.of("message", "frame de áudio de stream não anunciado"))))
                    .as(type.name()).isEmpty();
        }
    }

    @AcceptanceCriteria("SPEC-012/CA-2")
    @Test
    void fragmentosERaciocinioNuncaViramFala() {
        interpreter.accept(event(EventType.USER_COMMAND, Map.of("turnId", "t1", "source", "voice", "text", "oi")));

        List<ActivityInterpreter.Output> thinking = interpreter.accept(event(EventType.AI_THINKING,
                Map.of("turnId", "t1", "thought", "vou verificar o arquivo /etc/passwd")));
        List<ActivityInterpreter.Output> delta = interpreter.accept(event(EventType.AI_RESPONSE,
                Map.of("turnId", "t1", "delta", "Primeiro vou pensar sobre")));

        assertThat(thinking).noneMatch(ActivityInterpreter.Say.class::isInstance);
        assertThat(delta).isEmpty();
    }

    @AcceptanceCriteria("SPEC-012/CA-2")
    @Test
    void errosEResultadosUsamModelosDeFrase() {
        interpreter.accept(event(EventType.USER_COMMAND, Map.of("turnId", "t1", "source", "voice", "text", "oi")));
        assertThat(says(event(EventType.AI_ERROR, Map.of("turnId", "t1", "kind", "NO_CREDENTIALS",
                "message", "defina ANTHROPIC_API_KEY em ~/.zordon/secrets.env"))))
                .containsExactly(ActivityInterpreter.NO_PROVIDER);

        assertThat(says(event(EventType.VOICE_STATE, Map.of("activity", "idle",
                "lastTest", Map.of("verdict", "ok", "peakDbfs", -12.0))))).containsExactly(ActivityInterpreter.MIC_OK);
        // O mesmo resultado não é dito de novo a cada snapshot.
        assertThat(says(event(EventType.VOICE_STATE, Map.of("activity", "idle",
                "lastTest", Map.of("verdict", "ok", "peakDbfs", -12.0))))).isEmpty();
    }

    @AcceptanceCriteria("SPEC-012/CA-4")
    @Test
    void respostaDeTurnoDeVozEFaladaEDeTurnoDigitadoNao() {
        interpreter.accept(event(EventType.USER_COMMAND, Map.of("turnId", "v", "source", "voice", "text", "horas")));
        interpreter.accept(event(EventType.USER_COMMAND, Map.of("turnId", "d", "source", "text", "text", "horas")));

        assertThat(says(event(EventType.AI_RESPONSE, Map.of("turnId", "v", "done", true, "text", "São 15h40."))))
                .containsExactly("São 15h40.");
        assertThat(says(event(EventType.AI_RESPONSE, Map.of("turnId", "d", "done", true, "text", "São 15h40."))))
                .isEmpty();
    }

    @Test
    void turnoDeVozDemoradoAvisaUmaVez() {
        interpreter.accept(event(EventType.USER_COMMAND, Map.of("turnId", "t1", "source", "voice", "text", "oi")));

        assertThat(interpreter.tick(T0.plus(Duration.ofSeconds(7)))).isEmpty();
        assertThat(interpreter.tick(T0.plus(Duration.ofSeconds(8)))).containsExactly(new ActivityInterpreter.Say(
                new Narration(ActivityInterpreter.STILL_WORKING, Narration.Priority.NORMAL, "etapa")));
        assertThat(interpreter.tick(T0.plus(Duration.ofSeconds(20)))).isEmpty();
    }

    private List<String> states(EventEnvelope event) {
        return interpreter.accept(event).stream()
                .filter(ActivityInterpreter.Show.class::isInstance)
                .map(output -> ((ActivityInterpreter.Show) output).state().name())
                .toList();
    }

    private List<String> says(EventEnvelope event) {
        return interpreter.accept(event).stream()
                .filter(ActivityInterpreter.Say.class::isInstance)
                .map(output -> ((ActivityInterpreter.Say) output).narration().text())
                .toList();
    }

    private EventEnvelope event(EventType type, Map<String, Object> payload) {
        return new EventEnvelope(++seq, T0, type, payload);
    }
}
