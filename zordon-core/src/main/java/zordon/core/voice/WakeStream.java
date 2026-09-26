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

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;

/**
 * O fluxo contínuo do motor nos modos {@code wake} e {@code open} (SPEC-013): a
 * palavra de ativação, o comando dentro do fluxo e o barge-in. Sob o lock do
 * {@link VoiceService}; os callbacks do motor o tomam.
 */
final class WakeStream {

    /** Fluxo contínuo aberto no motor; {@code null} fora de {@code wake}/{@code open}. */
    private record ActiveStream(long id, VoiceEngine.StreamMode mode) {}

    private enum Command { NONE, LISTENING, TRANSCRIBING }

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final VoiceEngine engine;
    private final AudioIngest ingest;
    private final VoiceCommands commands;
    private final VoiceHooks hooks;
    private ActiveStream stream;
    /** O comando em curso dentro do fluxo: ouvindo, transcrevendo ou nenhum. */
    private Command command = Command.NONE;
    /** Pontuação da palavra que abriu o comando em curso; {@code null} se foi clique ou modo aberto. */
    private Double wakeScore;
    private boolean bargeIn;
    private boolean speaking;
    private boolean speakingArmed = true;

    WakeStream(VoiceEngine engine, AudioIngest ingest, VoiceCommands commands, VoiceHooks hooks) {
        this.engine = engine;
        this.ingest = ingest;
        this.commands = commands;
        this.hooks = hooks;
    }

    boolean open() {
        return stream != null;
    }

    /** O motor pronto e com a palavra de ativação: só assim o fluxo contínuo existe. */
    boolean ready() {
        return engine.status().state() == VoiceEngine.State.READY && engine.wakeWord();
    }

    VoiceEngine.Status engineStatus() {
        return engine.status();
    }

    boolean wakeWord() {
        return engine.wakeWord();
    }

    VoiceEngine.Activity engineActivity() {
        return engine.activity();
    }

    boolean speaking() {
        return speaking;
    }

    /** Com o fluxo aberto, o clique abre o comando nele: o áudio já está chegando. @return se abriu */
    boolean listenNow() {
        if (command != Command.NONE) {
            return false;
        }
        engine.listenNow(stream.id());
        command = Command.LISTENING;
        wakeScore = null;
        ingest.levels(true);
        return true;
    }

    /**
     * @param armed se a palavra pode interromper esta fala; falso quando a fala
     *     contém "Zordon", para ele não se acordar sozinho (SPEC-013 CA-9)
     */
    void speaking(boolean now, boolean armed) {
        speaking = now;
        speakingArmed = !now || armed;
        if (stream != null) {
            engine.duplex(stream.id(), now, speakingArmed);
        }
    }

    /** {@code LISTENING} ou {@code THINKING} com um comando em curso; {@code null} sem comando. */
    VoiceEngine.Activity commandActivity() {
        return switch (command) {
            case LISTENING -> VoiceEngine.Activity.LISTENING;
            case TRANSCRIBING -> VoiceEngine.Activity.THINKING;
            case NONE -> null;
        };
    }

    /**
     * Abre, troca ou fecha o fluxo conforme o modo em vigor e a captura confirmada.
     *
     * @param captureOn o microfone confirmado ligado, sem desacordo do host
     * @param clickActive há uma escuta por clique em curso: o fluxo espera ela acabar
     */
    void sync(boolean captureOn, VoiceMode desired, boolean clickActive) {
        VoiceEngine.StreamMode wanted = wanted(captureOn, desired);
        if (stream != null && wanted != stream.mode()) {
            if (wanted != null && command != Command.NONE) {
                return;     // troca de modo espera o comando em curso terminar
            }
            close(clickActive);
        }
        if (stream == null && wanted != null && !clickActive) {
            start(wanted);
        }
    }

    private VoiceEngine.StreamMode wanted(boolean captureOn, VoiceMode desired) {
        if (!captureOn || !ready()) {
            return null;
        }
        return desired == VoiceMode.OPEN ? VoiceEngine.StreamMode.OPEN
                : desired == VoiceMode.WAKE ? VoiceEngine.StreamMode.WAKE : null;
    }

    private void close(boolean clickActive) {
        log.info("fluxo {} fechado ({})", stream.id(), stream.mode().wire());
        engine.cancel(stream.id());
        stream = null;
        command = Command.NONE;
        wakeScore = null;
        if (!clickActive) {
            ingest.forward(null);
            ingest.levels(hooks.testing().getAsBoolean());
        }
    }

    private void start(VoiceEngine.StreamMode wanted) {
        long id = commands.nextId();
        stream = new ActiveStream(id, wanted);
        if (!engine.stream(id, wanted, new StreamListener(id))) {
            log.warn("o motor recusou o fluxo {}", wanted.wire());
            stream = null;
            return;
        }
        ingest.forward(pcm -> engine.audio(id, pcm));
        if (speaking) {
            engine.duplex(id, true, speakingArmed);
        }
        log.info("fluxo {} aberto ({})", id, wanted.wire());
    }

    /** O que o motor conta sobre o fluxo. Roda na thread do motor. */
    @Spec("SPEC-013")
    private final class StreamListener implements VoiceEngine.Stream {

        private final long id;

        StreamListener(long id) {
            this.id = id;
        }

        @Override
        public void speech() {
            synchronized (hooks.lock()) {
                if (stream == null || stream.id() != id) {
                    return;
                }
                command = Command.LISTENING;
                ingest.levels(true);
                hooks.publish().run();
            }
        }

        @Override
        public void wake(double score) {
            boolean interrupting;
            synchronized (hooks.lock()) {
                if (stream == null || stream.id() != id) {
                    return;
                }
                interrupting = speaking;
                command = Command.LISTENING;
                wakeScore = score;
                bargeIn = interrupting;
                ingest.levels(true);
                log.info("palavra de ativação ({}){}", score, interrupting ? ", interrompendo a fala" : "");
                hooks.publish().run();
            }
            commands.attention(interrupting);
        }

        @Override
        public void ended() {
            synchronized (hooks.lock()) {
                if (stream != null && stream.id() == id && command == Command.LISTENING) {
                    command = Command.TRANSCRIBING;
                    ingest.levels(hooks.testing().getAsBoolean());
                    hooks.publish().run();
                }
            }
        }

        @Override
        public void transcript(VoiceEngine.Transcript transcript) {
            String text;
            synchronized (hooks.lock()) {
                if (stream == null || stream.id() != id) {
                    return;
                }
                boolean wasCommand = command != Command.NONE;
                String outcome = VoiceCommands.outcome(transcript);
                command = Command.NONE;
                text = "command".equals(outcome) ? transcript.text().strip() : null;
                commands.dispatching(text != null);
                if (wasCommand || text != null || "low_confidence".equals(outcome)) {
                    commands.transcript(VoiceCommands.transcriptEvent(transcript, outcome));
                }
                if (wakeScore != null) {
                    commands.woke(Map.of("score", wakeScore, "outcome", outcome, "bargeIn", bargeIn));
                }
                wakeScore = null;
                bargeIn = false;
                ingest.levels(hooks.testing().getAsBoolean());
                hooks.resync().run();
                hooks.publish().run();
            }
            if (text != null) {
                commands.deliver(text);
            }
        }

        @Override
        public void closed(String reason) {
            synchronized (hooks.lock()) {
                if (stream == null || stream.id() != id) {
                    return;
                }
                log.info("o motor encerrou o fluxo {}: {}", id, reason);
                stream = null;
                command = Command.NONE;
                wakeScore = null;
                if (!hooks.clickActive().getAsBoolean()) {
                    ingest.forward(null);
                    ingest.levels(hooks.testing().getAsBoolean());
                }
                hooks.publish().run();
            }
        }
    }
}
