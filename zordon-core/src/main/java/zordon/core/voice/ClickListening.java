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

import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.zwp.ZwpMethodException;

/**
 * A escuta pedida por clique (SPEC-011 §3): liga o microfone até o motor dizer
 * que a fala acabou, ou no máximo {@link VoiceService#MAX_LISTENING}. Sob o lock
 * do {@link VoiceService}; os callbacks do motor o tomam.
 */
final class ClickListening {

    private enum Phase { CAPTURING, TRANSCRIBING }

    /** A escuta em curso; no máximo uma. */
    private record Listening(long id, Phase phase, long deadline) {}

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final VoiceEngine engine;
    private final AudioIngest ingest;
    private final LongSupplier ticker;
    private final VoiceCommands commands;
    private final VoiceHooks hooks;
    private Listening listening;

    ClickListening(VoiceEngine engine, AudioIngest ingest, LongSupplier ticker, VoiceCommands commands,
            VoiceHooks hooks) {
        this.engine = engine;
        this.ingest = ingest;
        this.ticker = ticker;
        this.commands = commands;
        this.hooks = hooks;
    }

    boolean active() {
        return listening != null;
    }

    boolean capturing() {
        return listening != null && listening.phase() == Phase.CAPTURING;
    }

    /** Sem motor pronto, não há escuta: o motivo vai para quem clicou. */
    void requireReady() {
        VoiceEngine.Status status = engine.status();
        if (status.state() != VoiceEngine.State.READY) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_AI_UNAVAILABLE,
                    status.reason() == null ? "motor de voz indisponível" : status.reason());
        }
    }

    void begin() {
        long id = commands.nextId();
        listening = new Listening(id, Phase.CAPTURING, ticker.getAsLong() + VoiceService.MAX_LISTENING.toNanos());
        boolean accepted = engine.listen(id, new VoiceEngine.Listening() {
            @Override
            public void ended() {
                speechEnded(id);
            }

            @Override
            public void transcript(VoiceEngine.Transcript transcript) {
                transcribed(id, transcript);
            }
        });
        if (!accepted) {
            listening = null;
            throw new ZwpMethodException(ZwpErrorKind.ERR_AI_UNAVAILABLE, "o motor de voz recusou a escuta");
        }
        ingest.forward(pcm -> engine.audio(id, pcm));
        ingest.levels(true);
        log.info("escuta {} iniciada", id);
    }

    /** Encerra a escuta já; o motor transcreve o que ouviu. */
    void stop() {
        if (capturing()) {
            engine.stop(listening.id());
        }
    }

    private void speechEnded(long id) {
        synchronized (hooks.lock()) {
            if (listening == null || listening.id() != id || listening.phase() != Phase.CAPTURING) {
                return;
            }
            // A fala acabou: o microfone desliga antes da transcrição, não depois.
            listening = new Listening(id, Phase.TRANSCRIBING, ticker.getAsLong() + VoiceService.MAX_LISTENING.toNanos());
            ingest.forward(null);
            ingest.levels(hooks.testing().getAsBoolean());
            hooks.reconcile().run();
        }
    }

    private void transcribed(long id, VoiceEngine.Transcript transcript) {
        String command;
        synchronized (hooks.lock()) {
            if (listening == null || listening.id() != id) {
                return;
            }
            String outcome = VoiceCommands.outcome(transcript);
            command = "command".equals(outcome) ? transcript.text().strip() : null;
            commands.dispatching(command != null);
            end(null);
            commands.transcript(VoiceCommands.transcriptEvent(transcript, outcome));
            log.info("escuta {} terminou: {} ({} ms de áudio)", id, transcript.reason(), transcript.durationMs());
        }
        // Fora do lock: o turno publica eventos e pode demorar.
        if (command != null) {
            commands.deliver(command);
        }
    }

    /** A escuta acabou sem transcrição: o motor é avisado e o microfone volta ao que o modo pede. */
    void cancel(String why) {
        if (listening != null) {
            engine.cancel(listening.id());
            end(why);
        }
    }

    void engineChanged() {
        if (listening != null && engine.status().state() != VoiceEngine.State.READY) {
            end("o motor de voz saiu");
        }
    }

    boolean expireIfDue() {
        if (listening != null && ticker.getAsLong() - listening.deadline() >= 0) {
            if (listening.phase() == Phase.CAPTURING) {
                // Rede de segurança: o motor não encerrou a tempo; pede para encerrar.
                engine.stop(listening.id());
                listening = new Listening(listening.id(), Phase.TRANSCRIBING,
                        ticker.getAsLong() + VoiceService.MAX_LISTENING.toNanos());
            } else {
                engine.cancel(listening.id());
                end("o motor não transcreveu a tempo");
            }
            return true;
        }
        return false;
    }

    /** Nanossegundos até o prazo da escuta; {@code MAX_VALUE} sem escuta. */
    long remaining(long now) {
        return listening == null ? Long.MAX_VALUE : listening.deadline() - now;
    }

    /** {@code LISTENING} capturando, {@code THINKING} transcrevendo, {@code null} sem escuta. */
    VoiceEngine.Activity activity() {
        if (listening == null) {
            return null;
        }
        return listening.phase() == Phase.CAPTURING ? VoiceEngine.Activity.LISTENING : VoiceEngine.Activity.THINKING;
    }

    private void end(String why) {
        if (listening == null) {
            return;
        }
        if (why != null) {
            log.info("escuta {} encerrada: {}", listening.id(), why);
        }
        listening = null;
        ingest.forward(null);
        ingest.levels(hooks.testing().getAsBoolean());
        hooks.reconcile().run();
    }
}
