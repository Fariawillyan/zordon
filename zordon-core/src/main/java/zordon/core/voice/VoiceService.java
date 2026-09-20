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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.zwp.ClientRequests;
import zordon.core.zwp.ZwpMethodException;

/**
 * Estado da voz: o modo que o usuário quer, o que está valendo de fato e o que o
 * host confirmou sobre o microfone.
 *
 * <p>Duas regras de privacidade moldam a classe (SPEC-006 §5): a captura só é
 * pedida quando há motor para consumir o áudio, e {@code off} só é afirmado com
 * confirmação do host.
 */
@Spec("SPEC-006")
public final class VoiceService {

    public static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration DEVICE_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration OPEN_DURATION = Duration.ofMinutes(5);
    public static final String NO_HOST = "host do Windows não conectado";
    public static final int MAX_TEST_SECONDS = 10;
    static final double SILENT_PEAK_DBFS = -60;
    static final double LOW_AVERAGE_DBFS = -45;

    public enum Capture {
        ON,
        OFF,
        PENDING,
        UNKNOWN
    }

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final VoiceStore store;
    private final VoiceEngine engine;
    private final ClientRequests clients;
    private final Consumer<Map<String, Object>> publisher;
    private final Clock clock;
    /**
     * Relógio monotônico dos prazos. O relógio de parede do WSL salta a cada
     * ~30 s, inclusive para trás (R7); um prazo medido nele pode nunca vencer.
     */
    private final java.util.function.LongSupplier ticker;
    private final ScheduledExecutorService scheduler;
    private final AudioIngest ingest;

    // Tudo abaixo é protegido por this.
    private VoiceMode persisted;
    private String deviceId;
    private Instant openUntil;
    private long openDeadline;
    /** Sessões de host em ordem de chegada; a última controla o microfone. */
    private final Deque<String> hosts = new ArrayDeque<>();
    private String deviceName;
    private Capture capture = Capture.UNKNOWN;
    private Boolean requested;
    private Instant confirmedAt;
    private String hostDisagrees;
    /** Só a resposta ao pedido mais recente conta; as anteriores chegam tarde demais. */
    private long generation;
    private Map<String, Object> lastPublished;
    private int lastStream;
    /** Fim do teste do microfone em andamento (SPEC-009); {@code null} sem teste. */
    private Instant testUntil;
    private long testDeadline;
    private Map<String, Object> lastTest;

    /** Teto da escuta pedida por clique (SPEC-011 §3): o VAD encerra antes, e isto é a rede. */
    public static final java.time.Duration MAX_LISTENING = java.time.Duration.ofSeconds(16);
    public static final String NO_WAKE_WORD =
            "modelo da palavra de ativação ausente; clique na esfera para falar";
    public static final String NO_PUSH =
            "o modo push chega com o atalho global; diga \"Zordon\" ou clique na esfera";
    /** Abaixo disso a transcrição não age: o Zordon diz que não entendeu (SPEC-013 §3). */
    public static final double MIN_CONFIDENCE = 0.6;

    private enum Phase { CAPTURING, TRANSCRIBING }

    /** A escuta em curso; no máximo uma. */
    private record Listening(long id, Phase phase, long deadline) {}

    private Listening listening;
    private long lastListen;

    /** Fluxo contínuo aberto no motor (SPEC-013); {@code null} fora de {@code wake}/{@code open}. */
    private record ActiveStream(long id, VoiceEngine.StreamMode mode) {}

    private enum Command { NONE, LISTENING, TRANSCRIBING }

    private ActiveStream stream;
    /** O comando em curso dentro do fluxo: ouvindo, transcrevendo ou nenhum. */
    private Command command = Command.NONE;
    /** Pontuação da palavra que abriu o comando em curso; {@code null} se foi clique ou modo aberto. */
    private Double wakeScore;
    private boolean bargeIn;
    private boolean speaking;
    private boolean speakingArmed = true;
    /** Transcrição pronta, comando ainda não entregue: continua "entendendo", sem piscar o repouso. */
    private boolean dispatching;
    /** Hosts que tocam áudio ({@code audio.playback}), em ordem de chegada. */
    private final Deque<String> playbackHosts = new ArrayDeque<>();
    private volatile Consumer<String> commands = text -> { };
    private volatile Consumer<Map<String, Object>> transcripts = transcript -> { };
    private volatile Consumer<Boolean> attention = interrupting -> { };
    private volatile Consumer<Map<String, Object>> wakes = wake -> { };

    /**
     * @param scheduler vence o prazo do modo {@code open}; {@code null} nos testes,
     *     que avançam o relógio e consultam {@link #status()}.
     */
    public VoiceService(
            VoiceStore store,
            VoiceEngine engine,
            ClientRequests clients,
            Consumer<Map<String, Object>> publisher,
            Clock clock,
            ScheduledExecutorService scheduler) {
        this(store, engine, clients, publisher, clock, scheduler, AudioIngest.detached());
    }

    public VoiceService(
            VoiceStore store,
            VoiceEngine engine,
            ClientRequests clients,
            Consumer<Map<String, Object>> publisher,
            Clock clock,
            ScheduledExecutorService scheduler,
            AudioIngest ingest) {
        // Só para testes com relógio falso: o prazo anda junto com o relógio deles.
        this(store, engine, clients, publisher, clock, scheduler, ingest, () -> clock.millis() * 1_000_000L);
    }

    /** @param ticker relógio monotônico em nanossegundos; em produção, {@code System::nanoTime}. */
    public VoiceService(
            VoiceStore store,
            VoiceEngine engine,
            ClientRequests clients,
            Consumer<Map<String, Object>> publisher,
            Clock clock,
            ScheduledExecutorService scheduler,
            AudioIngest ingest,
            java.util.function.LongSupplier ticker) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.ingest = Objects.requireNonNull(ingest, "ingest");
        this.store = Objects.requireNonNull(store, "store");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = scheduler;
        VoiceStore.Saved saved = store.load();
        this.persisted = saved.mode();
        this.deviceId = saved.deviceId();
        engine.onChange(this::engineChanged);
    }

    public synchronized Map<String, Object> status() {
        boolean openExpired = expireOpenIfDue();
        boolean testExpired = expireTestIfDue();
        boolean listeningExpired = expireListeningIfDue();
        if (openExpired || testExpired || listeningExpired) {
            reconcile();
        }
        return snapshot();
    }

    /**
     * Liga o microfone por {@code seconds}, mesmo sem motor, para o usuário ver o
     * nível que chega ao núcleo (SPEC-009 §3). Visível e com teto de 10 s.
     */
    public synchronized Map<String, Object> testMicrophone(int seconds) {
        if (seconds < 1 || seconds > MAX_TEST_SECONDS) {
            throw new IllegalArgumentException("seconds precisa estar entre 1 e " + MAX_TEST_SECONDS);
        }
        if (hosts.isEmpty()) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE, NO_HOST);
        }
        if (testUntil == null) {
            ingest.resetStats();
        }
        testUntil = clock.instant().plusSeconds(seconds);
        testDeadline = ticker.getAsLong() + TimeUnit.SECONDS.toNanos(seconds);
        ingest.levels(true);
        scheduleCheck(TimeUnit.SECONDS.toNanos(seconds));
        log.info("teste do microfone por {} s", seconds);
        reconcile();
        return snapshot();
    }

    public synchronized Map<String, Object> setMode(VoiceMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (mode == VoiceMode.OPEN) {
            openUntil = clock.instant().plus(OPEN_DURATION);
            openDeadline = ticker.getAsLong() + OPEN_DURATION.toNanos();
            scheduleCheck(OPEN_DURATION.toNanos());
        } else {
            openUntil = null;
            persisted = mode;
            store.save(new VoiceStore.Saved(persisted, deviceId));
        }
        // "Desligar microfone" desliga de verdade, inclusive um teste em andamento.
        if (mode == VoiceMode.OFF && testUntil != null) {
            finishTest("interrompido: microfone desligado pelo usuário");
        }
        reconcile();
        return snapshot();
    }

    /** Para onde vai a transcrição de uma escuta: um turno com {@code source: "voice"}. */
    public void onCommand(Consumer<String> handler) {
        this.commands = Objects.requireNonNull(handler, "handler");
    }

    /** Recebe cada transcrição, para o evento {@code VOICE_STOPPED}. */
    public void onTranscript(Consumer<Map<String, Object>> handler) {
        this.transcripts = Objects.requireNonNull(handler, "handler");
    }

    /**
     * A palavra foi dita: toca o tom de escuta e, se o Zordon falava
     * ({@code true}), corta a fala (SPEC-013 §3). Chamado fora do lock.
     */
    public void onWake(Consumer<Boolean> handler) {
        this.attention = Objects.requireNonNull(handler, "handler");
    }

    /** Cada ativação pela palavra, com o desfecho, para o evento {@code VOICE_WAKE}. */
    public void onWakeOutcome(Consumer<Map<String, Object>> handler) {
        this.wakes = Objects.requireNonNull(handler, "handler");
    }

    /**
     * Escuta pedida por clique (SPEC-011 §3): liga o microfone até o motor dizer
     * que a fala acabou, ou no máximo {@link #MAX_LISTENING}.
     */
    public synchronized Map<String, Object> startListening() {
        if (hosts.isEmpty()) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE, NO_HOST);
        }
        VoiceEngine.Status status = engine.status();
        if (status.state() != VoiceEngine.State.READY) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_AI_UNAVAILABLE,
                    status.reason() == null ? "motor de voz indisponível" : status.reason());
        }
        if (listening != null) {
            return snapshot();
        }
        if (stream != null) {
            // Com o fluxo aberto, o clique abre o comando nele: o áudio já está chegando.
            if (command == Command.NONE) {
                engine.listenNow(stream.id());
                command = Command.LISTENING;
                wakeScore = null;
                ingest.levels(true);
                publishIfChanged();
            }
            return snapshot();
        }
        long id = ++lastListen;
        listening = new Listening(id, Phase.CAPTURING, ticker.getAsLong() + MAX_LISTENING.toNanos());
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
        scheduleCheck(MAX_LISTENING.toNanos());
        log.info("escuta {} iniciada", id);
        reconcile();
        return snapshot();
    }

    /** Encerra a escuta já; o motor transcreve o que ouviu. */
    public synchronized Map<String, Object> stopListening() {
        if (listening != null && listening.phase() == Phase.CAPTURING) {
            engine.stop(listening.id());
        }
        return snapshot();
    }

    /** O narrador está falando (SPEC-012): vira {@code activity: speaking}. */
    public synchronized void speaking(boolean now) {
        speaking(now, true);
    }

    /**
     * @param armed se a palavra pode interromper esta fala; falso quando a fala
     *     contém "Zordon", para ele não se acordar sozinho (SPEC-013 CA-9)
     */
    public synchronized void speaking(boolean now, boolean armed) {
        speaking = now;
        speakingArmed = !now || armed;
        if (stream != null) {
            engine.duplex(stream.id(), now, speakingArmed);
        }
        publishIfChanged();
    }

    public synchronized void playbackHostConnected(String sessionId) {
        playbackHosts.remove(sessionId);
        playbackHosts.addLast(sessionId);
    }

    /** O host que toca a fala, o mais recente. */
    public synchronized java.util.Optional<String> playbackHost() {
        return java.util.Optional.ofNullable(playbackHosts.peekLast());
    }

    private synchronized void speechEnded(long id) {
        if (listening == null || listening.id() != id || listening.phase() != Phase.CAPTURING) {
            return;
        }
        // A fala acabou: o microfone desliga antes da transcrição, não depois.
        listening = new Listening(id, Phase.TRANSCRIBING, ticker.getAsLong() + MAX_LISTENING.toNanos());
        ingest.forward(null);
        ingest.levels(testUntil != null);
        reconcile();
    }

    private void transcribed(long id, VoiceEngine.Transcript transcript) {
        String command;
        synchronized (this) {
            if (listening == null || listening.id() != id) {
                return;
            }
            String outcome = outcome(transcript);
            command = "command".equals(outcome) ? transcript.text().strip() : null;
            dispatching = command != null;
            endListening(null);
            transcripts.accept(transcriptEvent(transcript, outcome));
            log.info("escuta {} terminou: {} ({} ms de áudio)", id, transcript.reason(), transcript.durationMs());
        }
        // Fora do lock: o turno publica eventos e pode demorar.
        if (command != null) {
            try {
                commands.accept(command);
            } finally {
                synchronized (this) {
                    dispatching = false;
                    publishIfChanged();
                }
            }
        }
    }

    /** {@code command}, {@code low_confidence} ou {@code silence}. */
    private static String outcome(VoiceEngine.Transcript transcript) {
        if (transcript.text().isBlank()) {
            return "silence";
        }
        return transcript.confidence() < MIN_CONFIDENCE ? "low_confidence" : "command";
    }

    /** O texto só vai para o evento quando vira comando: fala descartada não fica no trace (CA-11). */
    private static Map<String, Object> transcriptEvent(VoiceEngine.Transcript transcript, String outcome) {
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

    /** Abre, troca ou fecha o fluxo contínuo conforme o modo em vigor e a captura confirmada. */
    private void syncStream() {
        VoiceEngine.StreamMode wanted = null;
        if (capture == Capture.ON && hostDisagrees == null && !hosts.isEmpty() && streamReady()) {
            wanted = desired() == VoiceMode.OPEN ? VoiceEngine.StreamMode.OPEN
                    : desired() == VoiceMode.WAKE ? VoiceEngine.StreamMode.WAKE : null;
        }
        if (stream != null && wanted != stream.mode()) {
            if (wanted != null && command != Command.NONE) {
                return;     // troca de modo espera o comando em curso terminar
            }
            log.info("fluxo {} fechado ({})", stream.id(), stream.mode().wire());
            engine.cancel(stream.id());
            stream = null;
            command = Command.NONE;
            wakeScore = null;
            if (listening == null) {
                ingest.forward(null);
                ingest.levels(testUntil != null);
            }
        }
        if (stream == null && wanted != null && listening == null) {
            long id = ++lastListen;
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
    }

    private boolean streamReady() {
        return engine.status().state() == VoiceEngine.State.READY && engine.wakeWord();
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
            synchronized (VoiceService.this) {
                if (stream == null || stream.id() != id) {
                    return;
                }
                command = Command.LISTENING;
                ingest.levels(true);
                publishIfChanged();
            }
        }

        @Override
        public void wake(double score) {
            boolean interrupting;
            synchronized (VoiceService.this) {
                if (stream == null || stream.id() != id) {
                    return;
                }
                interrupting = speaking;
                command = Command.LISTENING;
                wakeScore = score;
                bargeIn = interrupting;
                ingest.levels(true);
                log.info("palavra de ativação ({}){}", score, interrupting ? ", interrompendo a fala" : "");
                publishIfChanged();
            }
            attention.accept(interrupting);
        }

        @Override
        public void ended() {
            synchronized (VoiceService.this) {
                if (stream != null && stream.id() == id && command == Command.LISTENING) {
                    command = Command.TRANSCRIBING;
                    ingest.levels(testUntil != null);
                    publishIfChanged();
                }
            }
        }

        @Override
        public void transcript(VoiceEngine.Transcript transcript) {
            String text;
            synchronized (VoiceService.this) {
                if (stream == null || stream.id() != id) {
                    return;
                }
                boolean wasCommand = command != Command.NONE;
                String outcome = outcome(transcript);
                command = Command.NONE;
                text = "command".equals(outcome) ? transcript.text().strip() : null;
                dispatching = text != null;
                if (wasCommand || text != null || "low_confidence".equals(outcome)) {
                    transcripts.accept(transcriptEvent(transcript, outcome));
                }
                if (wakeScore != null) {
                    wakes.accept(Map.of("score", wakeScore, "outcome", outcome, "bargeIn", bargeIn));
                }
                wakeScore = null;
                bargeIn = false;
                ingest.levels(testUntil != null);
                syncStream();
                publishIfChanged();
            }
            if (text != null) {
                try {
                    commands.accept(text);
                } finally {
                    synchronized (VoiceService.this) {
                        dispatching = false;
                        publishIfChanged();
                    }
                }
            }
        }

        @Override
        public void closed(String reason) {
            synchronized (VoiceService.this) {
                if (stream == null || stream.id() != id) {
                    return;
                }
                log.info("o motor encerrou o fluxo {}: {}", id, reason);
                stream = null;
                command = Command.NONE;
                wakeScore = null;
                if (listening == null) {
                    ingest.forward(null);
                    ingest.levels(testUntil != null);
                }
                publishIfChanged();
            }
        }
    }

    private void endListening(String why) {
        if (listening == null) {
            return;
        }
        if (why != null) {
            log.info("escuta {} encerrada: {}", listening.id(), why);
        }
        listening = null;
        ingest.forward(null);
        ingest.levels(testUntil != null);
        reconcile();
    }

    private VoiceEngine.Activity activity(boolean effectiveActive) {
        if (command == Command.LISTENING) {
            return VoiceEngine.Activity.LISTENING;
        }
        if (command == Command.TRANSCRIBING) {
            return VoiceEngine.Activity.THINKING;
        }
        if (listening != null) {
            return listening.phase() == Phase.CAPTURING ? VoiceEngine.Activity.LISTENING
                    : VoiceEngine.Activity.THINKING;
        }
        if (dispatching) {
            return VoiceEngine.Activity.THINKING;
        }
        if (speaking) {
            return VoiceEngine.Activity.SPEAKING;
        }
        return effectiveActive ? engine.activity() : VoiceEngine.Activity.IDLE;
    }

    private boolean expireListeningIfDue() {
        if (listening != null && ticker.getAsLong() - listening.deadline() >= 0) {
            if (listening.phase() == Phase.CAPTURING) {
                // Rede de segurança: o motor não encerrou a tempo; pede para encerrar.
                engine.stop(listening.id());
                listening = new Listening(listening.id(), Phase.TRANSCRIBING,
                        ticker.getAsLong() + MAX_LISTENING.toNanos());
            } else {
                engine.cancel(listening.id());
                endListening("o motor não transcreveu a tempo");
            }
            return true;
        }
        return false;
    }

    public synchronized void hostConnected(String sessionId) {
        String previous = hosts.peekLast();
        hosts.remove(sessionId);
        hosts.addLast(sessionId);
        if (previous != null && !previous.equals(sessionId)) {
            // Dois hosts: o mais recente controla, e o anterior larga o microfone.
            clients.request(previous, "audio.setCaptureEnabled", Map.of("enabled", false), CAPTURE_TIMEOUT)
                    .exceptionally(failure -> {
                        log.warn("host anterior não confirmou que largou o microfone: {}", message(failure));
                        return Map.of();
                    });
        }
        forgetHostState();
        applyPreferredDevice(sessionId);
        reconcile();
    }

    public synchronized void hostDisconnected(String sessionId) {
        playbackHosts.remove(sessionId);
        boolean wasActive = sessionId.equals(hosts.peekLast());
        if (!hosts.remove(sessionId)) {
            return;
        }
        if (wasActive) {
            forgetHostState();
            if (testUntil != null) {
                finishTest("o host desconectou durante o teste");
            }
            if (listening != null) {
                engine.cancel(listening.id());
                endListening("o host desconectou durante a escuta");
            }
            if (!hosts.isEmpty()) {
                applyPreferredDevice(hosts.peekLast());
            }
        }
        reconcile();
    }

    public CompletableFuture<Map<String, Object>> devices() {
        String host = activeHost();
        if (host == null) {
            return CompletableFuture.failedFuture(new ZwpMethodException(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE, NO_HOST));
        }
        return clients.request(host, "audio.listDevices", Map.of(), DEVICE_TIMEOUT);
    }

    public CompletableFuture<Map<String, Object>> selectDevice(String id) {
        if (id == null || id.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("deviceId é obrigatório"));
        }
        String host = activeHost();
        if (host == null) {
            return CompletableFuture.failedFuture(new ZwpMethodException(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE, NO_HOST));
        }
        return clients.request(host, "audio.selectDevice", Map.of("deviceId", id), DEVICE_TIMEOUT)
                .thenApply(result -> deviceSelected(host, id, result));
    }

    private synchronized Map<String, Object> deviceSelected(String host, String id, Map<String, Object> result) {
        deviceId = id;
        store.save(new VoiceStore.Saved(persisted, deviceId));
        if (host.equals(hosts.peekLast())) {
            deviceName = result.get("name") instanceof String name ? name : null;
        }
        publishIfChanged();
        return snapshot();
    }

    private synchronized String activeHost() {
        return hosts.peekLast();
    }

    private void forgetHostState() {
        ingest.close();
        generation++;
        capture = Capture.UNKNOWN;
        requested = null;
        confirmedAt = null;
        deviceName = null;
        hostDisagrees = null;
    }

    private void applyPreferredDevice(String host) {
        if (deviceId == null) {
            return;
        }
        clients.request(host, "audio.selectDevice", Map.of("deviceId", deviceId), DEVICE_TIMEOUT)
                .whenComplete((result, failure) -> preferredDeviceApplied(host, result, failure));
    }

    private synchronized void preferredDeviceApplied(String host, Map<String, Object> result, Throwable failure) {
        if (!host.equals(hosts.peekLast())) {
            return;
        }
        if (failure != null) {
            log.warn("dispositivo de voz preferido não foi aplicado; o host usa o padrão: {}", message(failure));
            return;
        }
        deviceName = result.get("name") instanceof String name ? name : null;
        publishIfChanged();
    }

    private synchronized void engineChanged() {
        if (listening != null && engine.status().state() != VoiceEngine.State.READY) {
            endListening("o motor de voz saiu");
        }
        reconcile();
    }

    /** Pede ao host a captura que o estado efetivo exige, se ainda não pediu. */
    private synchronized void reconcile() {
        String host = hosts.peekLast();
        if (host != null) {
            boolean wanted = captureWanted();
            if (requested == null || requested != wanted) {
                requestCapture(host, wanted);
            }
        }
        syncStream();
        publishIfChanged();
    }

    /** Com motor para ouvir, ou durante o teste pedido pelo usuário (SPEC-009 §5). */
    private boolean captureWanted() {
        if (hosts.isEmpty()) {
            return false;
        }
        return testUntil != null
                || listening != null && listening.phase() == Phase.CAPTURING
                || ((desired() == VoiceMode.WAKE || desired() == VoiceMode.OPEN) && streamReady());
    }

    private void requestCapture(String host, boolean enabled) {
        long request = ++generation;
        requested = enabled;
        capture = Capture.PENDING;
        hostDisagrees = null;
        publishIfChanged();
        Map<String, Object> params;
        if (enabled) {
            // Cada captura é um stream novo: frame de captura anterior não entra nesta.
            lastStream = lastStream % 0xFFFF + 1;
            ingest.open(host, lastStream);
            params = Map.of("enabled", true, "streamId", lastStream);
        } else {
            ingest.close();
            params = Map.of("enabled", false);
        }
        clients.request(host, "audio.setCaptureEnabled", params, CAPTURE_TIMEOUT)
                .whenComplete((result, failure) -> captureAnswered(request, enabled, result, failure));
    }

    private synchronized void captureAnswered(
            long request, boolean enabled, Map<String, Object> result, Throwable failure) {
        if (request != generation) {
            return;
        }
        if (failure != null) {
            capture = Capture.UNKNOWN;
            confirmedAt = null;
            // Sem confirmação, o próximo reconcile pede de novo.
            requested = null;
            log.warn("o host não confirmou {} o microfone: {}", enabled ? "ligar" : "desligar", message(failure));
        } else {
            boolean actual = Boolean.TRUE.equals(result.get("enabled"));
            capture = actual ? Capture.ON : Capture.OFF;
            confirmedAt = clock.instant();
            if (actual != enabled) {
                String base = actual ? "o host não desligou o microfone" : "o host não ligou o microfone";
                // O host sabe por quê (privacidade do Windows, dispositivo ocupado); SPEC-007 §7.
                hostDisagrees = result.get("reason") instanceof String reason && !reason.isBlank()
                        ? base + ": " + reason
                        : base;
                log.warn("{}", hostDisagrees);
                if (enabled && testUntil != null) {
                    finishTest(hostDisagrees);
                    reconcile();
                }
            }
        }
        syncStream();
        publishIfChanged();
    }

    private VoiceMode desired() {
        return openUntil != null ? VoiceMode.OPEN : persisted;
    }

    private boolean expireOpenIfDue() {
        if (openUntil != null && ticker.getAsLong() - openDeadline >= 0) {
            log.info("modo open venceu; voz volta a {}", persisted.wire());
            openUntil = null;
            return true;
        }
        return false;
    }

    private boolean expireTestIfDue() {
        if (testUntil != null && ticker.getAsLong() - testDeadline >= 0) {
            finishTest(null);
            return true;
        }
        return false;
    }

    /** Encerra o teste e guarda o resultado; {@code failure} quando ele não pôde medir. */
    private void finishTest(String failure) {
        testUntil = null;
        ingest.levels(false);
        AudioIngest.Stats stats = ingest.stats();
        Map<String, Object> result = new LinkedHashMap<>();
        if (failure == null && stats.frames() == 0) {
            failure = "nenhum áudio chegou do host";
        }
        if (failure != null) {
            result.put("verdict", "failed");
            result.put("message", failure);
        } else {
            result.put("peakDbfs", stats.peakDbfs());
            result.put("averageDbfs", stats.averageDbfs());
            if (stats.peakDbfs() < SILENT_PEAK_DBFS) {
                result.put("verdict", "silent");
                result.put("message", "nenhum sinal: microfone mudo ou errado");
            } else if (stats.averageDbfs() < LOW_AVERAGE_DBFS) {
                result.put("verdict", "low");
                result.put("message", "sinal baixo: aproxime-se ou aumente o ganho");
            } else {
                result.put("verdict", "ok");
                result.put("message", "sinal bom");
            }
        }
        result.put("frames", stats.frames());
        lastTest = result;
        log.info("teste do microfone: {} ({} frames)", result.get("verdict"), stats.frames());
    }

    /**
     * Confere os prazos daqui a {@code delayNanos} e, enquanto algum não tiver
     * vencido, confere de novo. Uma checagem única que chegasse cedo deixaria o
     * microfone ligado até alguém desligá-lo à mão.
     */
    private void scheduleCheck(long delayNanos) {
        if (scheduler == null) {
            return;
        }
        scheduler.schedule(() -> {
            status();
            synchronized (this) {
                long now = ticker.getAsLong();
                long next = Long.MAX_VALUE;
                if (testUntil != null) {
                    next = Math.min(next, testDeadline - now);
                }
                if (openUntil != null) {
                    next = Math.min(next, openDeadline - now);
                }
                if (listening != null) {
                    next = Math.min(next, listening.deadline() - now);
                }
                if (next != Long.MAX_VALUE) {
                    scheduleCheck(Math.max(next, TimeUnit.MILLISECONDS.toNanos(20)));
                }
            }
        }, delayNanos + TimeUnit.MILLISECONDS.toNanos(20), TimeUnit.NANOSECONDS);
    }

    private Map<String, Object> snapshot() {
        VoiceMode desired = desired();
        VoiceEngine.Status engineStatus = engine.status();
        String effective;
        String reason = null;
        if (hostDisagrees != null) {
            effective = "unavailable";
            reason = hostDisagrees;
        } else if (desired == VoiceMode.OFF) {
            effective = VoiceMode.OFF.wire();
        } else if (hosts.isEmpty()) {
            effective = "unavailable";
            reason = NO_HOST;
        } else if (desired == VoiceMode.PUSH) {
            effective = "unavailable";
            reason = NO_PUSH;
        } else if (engineStatus.state() != VoiceEngine.State.READY) {
            effective = "unavailable";
            reason = engineStatus.reason();
        } else if (!engine.wakeWord()) {
            effective = "unavailable";
            reason = NO_WAKE_WORD;
        } else {
            effective = desired.wire();
        }
        boolean active = !"off".equals(effective) && !"unavailable".equals(effective);

        Map<String, Object> captureState = new LinkedHashMap<>();
        captureState.put("state", capture.name().toLowerCase(java.util.Locale.ROOT));
        putIfPresent(captureState, "requested", requested);
        putIfPresent(captureState, "confirmedAt", confirmedAt);

        Map<String, Object> host = new LinkedHashMap<>();
        host.put("connected", !hosts.isEmpty());
        putIfPresent(host, "device", deviceName);

        Map<String, Object> engineState = new LinkedHashMap<>();
        engineState.put("state", engineStatus.state().wire());
        putIfPresent(engineState, "reason", engineStatus.reason());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mode", desired.wire());
        snapshot.put("effective", effective);
        putIfPresent(snapshot, "reason", reason);
        snapshot.put("activity", activity(active).wire());
        putIfPresent(snapshot, "openUntil", openUntil);
        snapshot.put("capture", captureState);
        snapshot.put("host", host);
        snapshot.put("engine", engineState);
        if (testUntil != null) {
            snapshot.put("test", Map.of("until", testUntil.toString()));
        }
        if (lastTest != null) {
            snapshot.put("lastTest", Map.copyOf(lastTest));
        }
        return snapshot;
    }

    /** Campo sem valor é omitido: o protocolo não carrega {@code null} (SPEC-006 §7). */
    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value instanceof Instant instant ? instant.toString() : value);
        }
    }

    private void publishIfChanged() {
        Map<String, Object> snapshot = snapshot();
        if (snapshot.equals(lastPublished)) {
            return;
        }
        lastPublished = snapshot;
        log.info("voz: modo {}, em vigor {}, captura {}{}",
                snapshot.get("mode"),
                snapshot.get("effective"),
                capture.name().toLowerCase(java.util.Locale.ROOT),
                snapshot.get("reason") == null ? "" : " — " + snapshot.get("reason"));
        publisher.accept(snapshot);
    }

    private static String message(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        return String.valueOf(cause.getMessage());
    }
}
