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
package zordon.core.voice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Para onde vai o que a voz ouviu: o comando vira turno, a transcrição e a
 * palavra de ativação viram eventos. Numera também as escutas e os fluxos, que
 * dividem o mesmo espaço de ids no motor.
 */
final class VoiceCommands {

    /** Abaixo disso a transcrição não age: o Zordon diz que não entendeu (SPEC-013 §3). */
    static final double MIN_CONFIDENCE = VoiceService.MIN_CONFIDENCE;

    private final VoiceHooks hooks;
    private volatile Consumer<String> commands = text -> { };
    private volatile Consumer<Map<String, Object>> transcripts = transcript -> { };
    private volatile Consumer<Boolean> attention = interrupting -> { };
    private volatile Consumer<Map<String, Object>> wakes = wake -> { };
    /** Transcrição pronta, comando ainda não entregue: continua "entendendo", sem piscar o repouso. */
    private boolean dispatching;
    private long lastListen;

    VoiceCommands(VoiceHooks hooks) {
        this.hooks = hooks;
    }

    void onCommand(Consumer<String> handler) {
        this.commands = Objects.requireNonNull(handler, "handler");
    }

    void onTranscript(Consumer<Map<String, Object>> handler) {
        this.transcripts = Objects.requireNonNull(handler, "handler");
    }

    void onWake(Consumer<Boolean> handler) {
        this.attention = Objects.requireNonNull(handler, "handler");
    }

    void onWakeOutcome(Consumer<Map<String, Object>> handler) {
        this.wakes = Objects.requireNonNull(handler, "handler");
    }

    /** Um id novo para uma escuta ou um fluxo. Sob o lock. */
    long nextId() {
        return ++lastListen;
    }

    boolean dispatching() {
        return dispatching;
    }

    void dispatching(boolean now) {
        dispatching = now;
    }

    void transcript(Map<String, Object> event) {
        transcripts.accept(event);
    }

    void woke(Map<String, Object> outcome) {
        wakes.accept(outcome);
    }

    /** Chamado fora do lock. */
    void attention(boolean interrupting) {
        attention.accept(interrupting);
    }

    /** Entrega o comando fora do lock: o turno publica eventos e pode demorar. */
    void deliver(String command) {
        try {
            commands.accept(command);
        } finally {
            synchronized (hooks.lock()) {
                dispatching = false;
                hooks.publish().run();
            }
        }
    }

    /** {@code command}, {@code low_confidence} ou {@code silence}. */
    static String outcome(VoiceEngine.Transcript transcript) {
        if (transcript.text().isBlank()) {
            return "silence";
        }
        return transcript.confidence() < MIN_CONFIDENCE ? "low_confidence" : "command";
    }

    /** O texto só vai para o evento quando vira comando: fala descartada não fica no trace (CA-11). */
    static Map<String, Object> transcriptEvent(VoiceEngine.Transcript transcript, String outcome) {
        Map<String, Object> event = new LinkedHashMap<>();
        if ("command".equals(outcome)) {
            event.put("text", transcript.text());
        }
        event.put("confidence", transcript.confidence());
        event.put("durationMs", transcript.durationMs());
        event.put("reason", transcript.reason());
        event.put("outcome", outcome);
        return event;
    }
}
