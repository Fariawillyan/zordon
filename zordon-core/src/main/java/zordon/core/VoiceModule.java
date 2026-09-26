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
package zordon.core;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import zordon.api.event.EventType;
import zordon.core.activity.ActivityService;
import zordon.core.trace.LiveTrace;
import zordon.core.voice.AudioIngest;
import zordon.core.voice.SidecarVoiceEngine;
import zordon.core.voice.SpeechPlayer;
import zordon.core.voice.VoiceService;
import zordon.core.voice.VoiceStore;
import zordon.core.zwp.VoiceMethods;

/** Conversa por voz: a captura, o motor, a fala, o narrador e o Live Trace. */
final class VoiceModule {

    private final CoreBase base;
    private final SidecarVoiceEngine engine;
    private final VoiceService voice;
    private final LiveTrace trace;
    private final SpeechPlayer speech;
    private final ActivityService activity;

    VoiceModule(CoreBase base) {
        this.base = base;
        AudioIngest audio = new AudioIngest(
                base.server(),
                level -> base.bus().publish(EventType.VOICE_LEVEL, level),
                alert -> base.bus().publish(EventType.SYSTEM_ALERT, alert),
                System::nanoTime);
        base.server().onBinary(audio);
        // O motor de voz é o sidecar zordon-voice, num socket Unix (SPEC-011, ADR-0028).
        this.engine = new SidecarVoiceEngine(Path.of(
                base.environment().getOrDefault("ZORDON_VOICE_SOCKET", "/run/zordon-voice/voice.sock")));
        this.voice = new VoiceService(new VoiceService.Dependencies(
                new VoiceStore(base.config().home().resolve("state").resolve("voice.json")), engine, base.server(),
                snapshot -> base.bus().publish(EventType.VOICE_STATE, snapshot), Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("voice-deadlines").factory()),
                audio, System::nanoTime));
        this.trace = new LiveTrace(base.config().home().resolve("trace"), Clock.systemDefaultZone());
        // A fala sai pelo narrador (SPEC-012) e é tocada no host pelo motor (SPEC-011).
        this.speech = new SpeechPlayer(engine, voice, base.server(), base.server()::sendBinary);
        this.activity = new ActivityService(base.bus(), speech, Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("activity-ticks").factory()));
    }

    VoiceService voice() {
        return voice;
    }

    Map<String, Object> traceReport() {
        return Map.of("dir", trace.dir().toString(), "file", trace.today().toString(), "activity", activity.state());
    }

    /** Trace, narrador e voz de pé, e o que a escuta ouve vai para {@code heard}. */
    void start(Consumer<String> heard) {
        trace.start(base.bus());
        activity.start();
        voice.onTranscript(transcript -> base.bus().publish(EventType.VOICE_STOPPED, transcript));
        voice.onWakeOutcome(wake -> base.bus().publish(EventType.VOICE_WAKE, wake));
        // A palavra (SPEC-013): se o Zordon falava, a fala para; depois, o tom de escuta.
        voice.onWake(interrupting -> {
            if (interrupting) {
                speech.interrupt();
            }
            speech.cue();
        });
        voice.onCommand(heard);
        engine.start();
        speech.start();
        new VoiceMethods(voice).registerOn(base.server());
    }

    void close() {
        speech.close();
        engine.close();
    }
}
