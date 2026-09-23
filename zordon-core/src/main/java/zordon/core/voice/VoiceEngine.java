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

import java.util.Locale;
import java.util.Objects;

/**
 * O que consome o áudio: VAD, wake word, STT e TTS. O sidecar {@code zordon-voice}
 * implementa isto; enquanto ele não existe, {@link AbsentVoiceEngine}.
 */
public interface VoiceEngine {

    enum State {
        ABSENT,
        STARTING,
        READY,
        FAILED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum Activity {
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** @param reason por que não está pronto, em português, para a interface; {@code null} se pronto. */
    record Status(State state, String reason) {

        public Status {
            Objects.requireNonNull(state, "state");
        }
    }

    /** Perfil de prosódia pedido ao sidecar para uma fala. */
    record SpeechStyle(String profile) {

        public SpeechStyle {
            Objects.requireNonNull(profile, "profile");
            if (profile.isBlank()) {
                throw new IllegalArgumentException("perfil de fala vazio");
            }
        }

        public static SpeechStyle normal() {
            return new SpeechStyle("normal");
        }

        public static SpeechStyle high() {
            return new SpeechStyle("high");
        }

        public static SpeechStyle authorization() {
            return new SpeechStyle("authorization");
        }

        public static SpeechStyle error() {
            return new SpeechStyle("error");
        }
    }

    Status status();

    Activity activity();

    /** Avisa quando estado ou atividade mudam. */
    void onChange(Runnable listener);

    /**
     * Se o motor ouve continuamente pela palavra de ativação (SPEC-013). Sem isso,
     * os modos {@code wake} e {@code open} não ligam o microfone: só a escuta
     * pedida por clique (SPEC-011 §5).
     */
    default boolean wakeWord() {
        return false;
    }

    /**
     * Transcrição de uma escuta (SPEC-011 §5).
     *
     * @param reason {@code end}, {@code max}, {@code stopped}, {@code silence},
     *     {@code not_ready} ou {@code lost}
     */
    record Transcript(String text, double confidence, long durationMs, String reason) {}

    /** Quem acompanha uma escuta. Chamado pela thread do motor. */
    interface Listening {

        /** A fala começou. */
        default void speech() {}

        /** A fala acabou: dá para desligar o microfone; a transcrição vem depois. */
        void ended();

        void transcript(Transcript transcript);
    }

    /** Modo de um fluxo contínuo (SPEC-013 §7). */
    enum StreamMode {
        /** Dormindo pela palavra; a palavra abre um comando. */
        WAKE,
        /** Toda fala é comando. */
        OPEN;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Quem acompanha um fluxo contínuo. Chamado pela thread do motor. */
    interface Stream {

        /** A palavra foi dita; se o Zordon falava, é barge-in. */
        void wake(double score);

        /** A fala do comando começou. */
        default void speech() {}

        /** A fala do comando acabou; a transcrição vem depois e o fluxo continua. */
        default void ended() {}

        /** Um comando transcrito, ou {@code silence} quando a palavra veio sem comando. */
        void transcript(Transcript transcript);

        /** O motor encerrou o fluxo: {@code no_wake_word}, {@code not_ready} ou {@code lost}. */
        void closed(String reason);
    }

    /** Quem recebe a fala sintetizada, PCM s16le mono na taxa informada. */
    interface Speech {

        void chunk(int rate, byte[] pcm);

        /** @param reason {@code null} no fim normal */
        void end(String reason);
    }

    /** Começa uma escuta; o áudio chega por {@link #audio}. @return falso se o motor não está pronto. */
    default boolean listen(long id, Listening listener) {
        return false;
    }

    default void audio(long id, byte[] pcm) {}

    /** Encerra a escuta já: transcreve o que houver. */
    default void stop(long id) {}

    /** Descarta a escuta ou o fluxo, sem transcrever. */
    default void cancel(long id) {}

    /** Abre um fluxo contínuo; o áudio chega por {@link #audio}. @return falso se o motor não pode. */
    default boolean stream(long id, StreamMode mode, Stream listener) {
        return false;
    }

    /**
     * O Zordon começou ou parou de falar. Falando, nada vira comando; só a
     * palavra interrompe, e só se {@code armed} (a fala não contém a palavra).
     */
    default void duplex(long id, boolean speaking, boolean armed) {}

    /** O usuário pediu para ouvir (clique) com o fluxo aberto: abre o comando já. */
    default void listenNow(long id) {}

    /** Sintetiza {@code text}. @return falso se o motor não está pronto. */
    default boolean speak(long id, String text, Speech speech) {
        return false;
    }

    /** Sintetiza {@code text} com um perfil; motores antigos usam o padrão. */
    default boolean speak(long id, String text, SpeechStyle style, Speech speech) {
        return speak(id, text, speech);
    }
}
