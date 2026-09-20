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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import zordon.core.zwp.ClientRequests;

/** Host, motor e relógio falsos para exercitar o {@link VoiceService} sem rede. */
final class VoiceFakes {

    private VoiceFakes() {}

    /** Registra cada pedido; responde sozinho só se houver um {@code responder}. */
    static final class Clients implements ClientRequests {

        record Call(String session, String method, Map<String, Object> params, CompletableFuture<Map<String, Object>> answer) {}

        final List<Call> calls = new CopyOnWriteArrayList<>();
        volatile Function<Call, Map<String, Object>> responder;

        /** Um host que faz o que lhe pedem. */
        static Clients obedient() {
            Clients clients = new Clients();
            clients.responder = call -> switch (call.method()) {
                case "audio.setCaptureEnabled" -> Map.of("enabled", call.params().get("enabled"));
                case "audio.selectDevice" -> Map.of("selected", call.params().get("deviceId"),
                        "name", "Microfone " + call.params().get("deviceId"));
                case "audio.listDevices" -> Map.of(
                        "devices", List.of(Map.of("id", "usb", "name", "Microfone USB", "default", true)),
                        "selected", "usb");
                default -> null;
            };
            return clients;
        }

        @Override
        public CompletableFuture<Map<String, Object>> request(
                String sessionId, String method, Map<String, Object> params, Duration timeout) {
            Call call = new Call(sessionId, method, params, new CompletableFuture<>());
            calls.add(call);
            Function<Call, Map<String, Object>> current = responder;
            Map<String, Object> answer = current == null ? null : current.apply(call);
            if (answer != null) {
                call.answer().complete(answer);
            }
            return call.answer();
        }

        List<Call> of(String method) {
            return calls.stream().filter(call -> call.method().equals(method)).toList();
        }

        Call last(String method) {
            List<Call> matching = of(method);
            return matching.isEmpty() ? null : matching.getLast();
        }
    }

    static final class Engine implements VoiceEngine {

        private volatile Status status = new Status(State.ABSENT, AbsentVoiceEngine.REASON);
        private volatile Activity activity = Activity.IDLE;
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        /** Os testes da SPEC-006 supõem um motor completo, que ouve pela palavra de ativação. */
        volatile boolean wakeWord = true;
        final java.util.Map<Long, Listening> listens = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final java.io.ByteArrayOutputStream heard = new java.io.ByteArrayOutputStream();
        final java.util.Map<Long, Speech> speeches = new java.util.concurrent.ConcurrentHashMap<>();

        final java.util.Map<Long, Stream> streams = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Map<Long, StreamMode> streamModes = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public boolean wakeWord() {
            return wakeWord;
        }

        @Override
        public boolean stream(long id, StreamMode mode, Stream listener) {
            if (status.state() != State.READY || !wakeWord) {
                return false;
            }
            calls.add("stream " + id + " " + mode.wire());
            streams.put(id, listener);
            streamModes.put(id, mode);
            return true;
        }

        @Override
        public void duplex(long id, boolean speaking, boolean armed) {
            calls.add("duplex " + id + " " + speaking + " " + armed);
        }

        @Override
        public void listenNow(long id) {
            calls.add("listenNow " + id);
        }

        /** O fluxo aberto mais recente. */
        Stream lastStream() {
            return streams.entrySet().stream().max(java.util.Map.Entry.comparingByKey())
                    .map(java.util.Map.Entry::getValue).orElseThrow();
        }

        long lastStreamId() {
            return streams.keySet().stream().mapToLong(Long::longValue).max().orElseThrow();
        }

        @Override
        public boolean listen(long id, Listening listener) {
            if (status.state() != State.READY) {
                return false;
            }
            calls.add("listen " + id);
            listens.put(id, listener);
            return true;
        }

        @Override
        public synchronized void audio(long id, byte[] pcm) {
            heard.writeBytes(pcm);
        }

        @Override
        public void stop(long id) {
            calls.add("stop " + id);
        }

        @Override
        public void cancel(long id) {
            calls.add("cancel " + id);
            listens.remove(id);
            streams.remove(id);
        }

        @Override
        public boolean speak(long id, String text, Speech speech) {
            if (status.state() != State.READY) {
                return false;
            }
            calls.add("speak " + text);
            speeches.put(id, speech);
            return true;
        }

        void notReady() {
            status = new Status(State.STARTING, "carregando");
            listeners.forEach(Runnable::run);
        }

        void ready() {
            status = new Status(State.READY, null);
            listeners.forEach(Runnable::run);
        }

        void activity(Activity next) {
            activity = next;
            listeners.forEach(Runnable::run);
        }

        @Override
        public Status status() {
            return status;
        }

        @Override
        public Activity activity() {
            return activity;
        }

        @Override
        public void onChange(Runnable listener) {
            listeners.add(listener);
        }
    }

    static final class MutableClock extends Clock {

        private volatile Instant now = Instant.parse("2026-09-18T17:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
