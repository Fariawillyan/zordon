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

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventEnvelope;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.api.trace.Spec;
import zordon.core.event.EventSubscription;
import zordon.core.event.QueuePolicy;
import zordon.core.event.ZordonEventBus;

/**
 * O fluxo voice-first do núcleo (ADR-0029): barramento → intérprete → narrador →
 * fala. Publica o estado visual e as narrações, e entrega cada narração ao TTS.
 */
@Spec("SPEC-012")
public final class ActivityService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

    private final ZordonEventBus bus;
    private final NarrationSink sink;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final ActivityInterpreter interpreter = new ActivityInterpreter();
    private final VoiceNarrator narrator = new VoiceNarrator();
    private EventSubscription subscription;

    public ActivityService(ZordonEventBus bus, NarrationSink sink, Clock clock, ScheduledExecutorService scheduler) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = scheduler;
    }

    public void start() {
        subscription = bus.subscribe("activity", Topic.ALL, QueuePolicy.dropOldest(1_024), this::accept);
        if (scheduler != null) {
            scheduler.scheduleAtFixedRate(this::tick, 250, 250, TimeUnit.MILLISECONDS);
        }
    }

    /** Público para testes que não querem esperar a fila do barramento. */
    public synchronized void accept(EventEnvelope event) {
        // Os próprios eventos e o nível do microfone não alimentam o intérprete: sem laço.
        if (event.type() == EventType.ACTIVITY_STATE
                || event.type() == EventType.VOICE_NARRATION
                || event.type() == EventType.VOICE_LEVEL) {
            return;
        }
        handle(interpreter.accept(event));
    }

    public synchronized void tick() {
        handle(interpreter.tick(clock.instant()));
        emit(narrator.tick(clock.instant()));
    }

    public synchronized String state() {
        return interpreter.state().wire();
    }

    private void handle(List<ActivityInterpreter.Output> outputs) {
        for (ActivityInterpreter.Output output : outputs) {
            switch (output) {
                case ActivityInterpreter.Show show ->
                        bus.publish(EventType.ACTIVITY_STATE, Map.of("state", show.state().wire()));
                case ActivityInterpreter.Say say -> emit(narrator.offer(say.narration(), clock.instant()));
            }
        }
    }

    private void emit(List<Narration> narrations) {
        for (Narration narration : narrations) {
            bus.publish(EventType.VOICE_NARRATION, narration.payload());
            log.info("narração {} ({})", narration.category(), narration.priority().wire());
            try {
                sink.speak(narration);
            } catch (RuntimeException e) {
                log.warn("fala não entregue ao motor de voz: {}", e.toString());
            }
        }
    }

    @Override
    public void close() {
        if (subscription != null) {
            bus.unsubscribe(subscription);
        }
    }
}
