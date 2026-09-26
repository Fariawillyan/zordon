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
import java.util.concurrent.ConcurrentHashMap;

/** O que espera resposta do motor: escutas, streams e falas em curso, e os eventos que chegam para elas. */
final class PendingVoiceCalls {

    private final Map<Long, VoiceEngine.Listening> listens = new ConcurrentHashMap<>();
    private final Map<Long, VoiceEngine.Speech> speeches = new ConcurrentHashMap<>();
    private final Map<Long, VoiceEngine.Stream> streams = new ConcurrentHashMap<>();

    void listen(long id, VoiceEngine.Listening listener) {
        listens.put(id, listener);
    }

    void stream(long id, VoiceEngine.Stream listener) {
        streams.put(id, listener);
    }

    void speak(long id, VoiceEngine.Speech speech) {
        speeches.put(id, speech);
    }

    void forgetListen(long id) {
        listens.remove(id);
    }

    void forgetStream(long id) {
        streams.remove(id);
    }

    void forgetSpeech(long id) {
        speeches.remove(id);
    }

    boolean listening(long id) {
        return listens.containsKey(id);
    }

    boolean streaming(long id) {
        return streams.containsKey(id);
    }

    /** Um evento do motor para uma chamada em curso. @return falso se o evento é desconhecido */
    boolean handle(String event, long id, Map<?, ?> header, byte[] payload) {
        switch (event) {
            case "wake" -> wakeDetected(id, header);
            case "speech" -> speechStarted(id);
            case "end" -> speechEnded(id);
            case "final" -> transcribed(id, header);
            case "stream_end" -> streamEnded(id, header);
            case "tts" -> ttsChunk(id, header, payload);
            case "tts_end" -> ttsEnded(id, header);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void wakeDetected(long id, Map<?, ?> header) {
        VoiceEngine.Stream stream = streams.get(id);
        if (stream != null) {
            stream.wake(header.get("score") instanceof Number score ? score.doubleValue() : 0);
        }
    }

    private void speechStarted(long id) {
        VoiceEngine.Listening listening = listens.get(id);
        if (listening != null) {
            listening.speech();
        }
        VoiceEngine.Stream stream = streams.get(id);
        if (stream != null) {
            stream.speech();
        }
    }

    private void speechEnded(long id) {
        VoiceEngine.Listening listening = listens.get(id);
        if (listening != null) {
            listening.ended();
        }
        VoiceEngine.Stream stream = streams.get(id);
        if (stream != null) {
            stream.ended();
        }
    }

    private void transcribed(long id, Map<?, ?> header) {
        VoiceEngine.Transcript transcript = new VoiceEngine.Transcript(
                header.get("text") instanceof String text ? text : "",
                header.get("confidence") instanceof Number c ? c.doubleValue() : 0,
                header.get("durationMs") instanceof Number d ? d.longValue() : 0,
                header.get("reason") instanceof String reason ? reason : "end");
        VoiceEngine.Listening listening = listens.remove(id);
        if (listening != null) {
            listening.transcript(transcript);
        }
        VoiceEngine.Stream stream = streams.get(id);
        if (stream != null) {
            stream.transcript(transcript);
        }
    }

    private void streamEnded(long id, Map<?, ?> header) {
        VoiceEngine.Stream stream = streams.remove(id);
        if (stream != null) {
            stream.closed(header.get("reason") instanceof String reason ? reason : "lost");
        }
    }

    private void ttsChunk(long id, Map<?, ?> header, byte[] payload) {
        VoiceEngine.Speech speech = speeches.get(id);
        if (speech != null) {
            speech.chunk(header.get("rate") instanceof Number rate ? rate.intValue() : 22_050,
                    payload);
        }
    }

    private void ttsEnded(long id, Map<?, ?> header) {
        VoiceEngine.Speech speech = speeches.remove(id);
        if (speech != null) {
            speech.end(header.get("reason") instanceof String reason ? reason : null);
        }
    }

    /** Escuta e fala em curso não têm mais quem as termine: terminam aqui, como perdidas. */
    void failAll() {
        listens.keySet().forEach(id -> {
            VoiceEngine.Listening listening = listens.remove(id);
            if (listening != null) {
                listening.transcript(new VoiceEngine.Transcript("", 0, 0, "lost"));
            }
        });
        speeches.keySet().forEach(id -> {
            VoiceEngine.Speech speech = speeches.remove(id);
            if (speech != null) {
                speech.end("lost");
            }
        });
        streams.keySet().forEach(id -> {
            VoiceEngine.Stream stream = streams.remove(id);
            if (stream != null) {
                stream.closed("lost");
            }
        });
    }
}
