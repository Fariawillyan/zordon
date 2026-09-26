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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
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
 *
 * <p>Esta classe é o lock de toda a voz e a ordem das coisas; cada parte mora na
 * sua: o que o usuário escolheu em {@link VoiceChoices}, os hosts e o microfone
 * em {@link HostCapture}, a escuta por clique em {@link ClickListening}, o fluxo
 * contínuo em {@link WakeStream} e o que foi ouvido em {@link VoiceCommands}.
 */
@Spec("SPEC-006")
public final class VoiceService {

    /**
     * Dependências externas do serviço, agrupadas para manter a construção explícita.
     *
     * @param scheduler vence os prazos; {@code null} nos testes, que avançam o relógio e consultam {@link #status()}
     * @param ticker relógio monotônico em nanossegundos; em produção, {@code System::nanoTime}
     */
    public record Dependencies(VoiceStore store, VoiceEngine engine, ClientRequests clients,
            Consumer<Map<String, Object>> publisher, Clock clock, ScheduledExecutorService scheduler,
            AudioIngest ingest, LongSupplier ticker) {}

    public static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration DEVICE_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration OPEN_DURATION = Duration.ofMinutes(5);
    public static final String NO_HOST = "host do Windows não conectado";
    public static final int MAX_TEST_SECONDS = 10;
    /** Teto da escuta pedida por clique (SPEC-011 §3): o VAD encerra antes, e isto é a rede. */
    public static final Duration MAX_LISTENING = Duration.ofSeconds(16);
    public static final String NO_WAKE_WORD =
            "modelo da palavra de ativação ausente; clique na esfera para falar";
    public static final String NO_PUSH =
            "o modo push chega com o atalho global; diga \"Zordon\" ou clique na esfera";
    /** Abaixo disso a transcrição não age: o Zordon diz que não entendeu (SPEC-013 §3). */
    public static final double MIN_CONFIDENCE = 0.6;

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final Consumer<Map<String, Object>> publisher;
    private final VoiceChoices choices;
    private final HostCapture hosts;
    private final VoiceCommands commands;
    private final ClickListening click;
    private final WakeStream stream;
    private final VoiceDeadlines deadlines;
    // Protegido por this.
    private Map<String, Object> lastPublished;

    public VoiceService(Dependencies dependencies) {
        Objects.requireNonNull(dependencies.ticker(), "ticker");
        Objects.requireNonNull(dependencies.ingest(), "ingest");
        Objects.requireNonNull(dependencies.engine(), "engine");
        this.publisher = Objects.requireNonNull(dependencies.publisher(), "publisher");
        VoiceHooks hooks = new VoiceHooks(this, this::publishIfChanged, this::reconcile, this::syncStream,
                this::testing, this::clickActive);
        this.choices = new VoiceChoices(Objects.requireNonNull(dependencies.store(), "store"), dependencies.ingest(),
                Objects.requireNonNull(dependencies.clock(), "clock"), dependencies.ticker());
        this.hosts = new HostCapture(Objects.requireNonNull(dependencies.clients(), "clients"), dependencies.ingest(),
                dependencies.clock(), this);
        this.commands = new VoiceCommands(hooks);
        this.click = new ClickListening(dependencies.engine(), dependencies.ingest(), dependencies.ticker(), commands,
                hooks);
        this.stream = new WakeStream(dependencies.engine(), dependencies.ingest(), commands, hooks);
        this.deadlines = new VoiceDeadlines(this, dependencies.scheduler(), dependencies.ticker(), choices, click);
        dependencies.engine().onChange(this::engineChanged);
    }

    public synchronized Map<String, Object> status() {
        boolean openExpired = choices.expireOpenIfDue();
        boolean testExpired = choices.expireTestIfDue();
        boolean listeningExpired = click.expireIfDue();
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
        if (!hosts.connected()) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE, NO_HOST);
        }
        choices.startTest(seconds);
        deadlines.schedule(TimeUnit.SECONDS.toNanos(seconds));
        log.info("teste do microfone por {} s", seconds);
        reconcile();
        return snapshot();
    }

    public synchronized Map<String, Object> setMode(VoiceMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (choices.choose(mode)) {
            deadlines.schedule(OPEN_DURATION.toNanos());
        }
        // "Desligar microfone" desliga de verdade, inclusive um teste em andamento.
        if (mode == VoiceMode.OFF && choices.testing()) {
            choices.finishTest("interrompido: microfone desligado pelo usuário");
        }
        reconcile();
        return snapshot();
    }

    /** Para onde vai a transcrição de uma escuta: um turno com {@code source: "voice"}. */
    public void onCommand(Consumer<String> handler) {
        commands.onCommand(handler);
    }

    /** Recebe cada transcrição, para o evento {@code VOICE_STOPPED}. */
    public void onTranscript(Consumer<Map<String, Object>> handler) {
        commands.onTranscript(handler);
    }

    /**
     * A palavra foi dita: toca o tom de escuta e, se o Zordon falava
     * ({@code true}), corta a fala (SPEC-013 §3). Chamado fora do lock.
     */
    public void onWake(Consumer<Boolean> handler) {
        commands.onWake(handler);
    }

    /** Cada ativação pela palavra, com o desfecho, para o evento {@code VOICE_WAKE}. */
    public void onWakeOutcome(Consumer<Map<String, Object>> handler) {
        commands.onWakeOutcome(handler);
    }

    /**
     * Escuta pedida por clique (SPEC-011 §3): liga o microfone até o motor dizer
     * que a fala acabou, ou no máximo {@link #MAX_LISTENING}.
     */
    public synchronized Map<String, Object> startListening() {
        if (!hosts.connected()) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE, NO_HOST);
        }
        click.requireReady();
        if (click.active()) {
            return snapshot();
        }
        if (stream.open()) {
            if (stream.listenNow()) {
                publishIfChanged();
            }
            return snapshot();
        }
        click.begin();
        deadlines.schedule(MAX_LISTENING.toNanos());
        reconcile();
        return snapshot();
    }

    /** Encerra a escuta já; o motor transcreve o que ouviu. */
    public synchronized Map<String, Object> stopListening() {
        click.stop();
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
        stream.speaking(now, armed);
        publishIfChanged();
    }

    public synchronized void playbackHostConnected(String sessionId) {
        hosts.playbackConnected(sessionId);
    }

    /** O host que toca a fala, o mais recente. */
    public synchronized Optional<String> playbackHost() {
        return hosts.playback();
    }

    public synchronized void hostConnected(String sessionId) {
        hosts.connect(sessionId);
        applyPreferredDevice(sessionId);
        reconcile();
    }

    public synchronized void hostDisconnected(String sessionId) {
        hosts.playbackGone(sessionId);
        boolean wasActive = hosts.isActive(sessionId);
        if (!hosts.remove(sessionId)) {
            return;
        }
        if (wasActive) {
            hosts.forget();
            if (choices.testing()) {
                choices.finishTest("o host desconectou durante o teste");
            }
            click.cancel("o host desconectou durante a escuta");
            if (hosts.connected()) {
                applyPreferredDevice(hosts.active());
            }
        }
        reconcile();
    }

    public CompletableFuture<Map<String, Object>> devices() {
        String host = hosts.activeLocked();
        if (host == null) {
            return CompletableFuture.failedFuture(new ZwpMethodException(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE, NO_HOST));
        }
        return hosts.ask(host, "audio.listDevices", Map.of());
    }

    public CompletableFuture<Map<String, Object>> selectDevice(String id) {
        if (id == null || id.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("deviceId é obrigatório"));
        }
        String host = hosts.activeLocked();
        if (host == null) {
            return CompletableFuture.failedFuture(new ZwpMethodException(ZwpErrorKind.ERR_BRIDGE_UNAVAILABLE, NO_HOST));
        }
        return hosts.ask(host, "audio.selectDevice", Map.of("deviceId", id))
                .thenApply(result -> deviceSelected(host, id, result));
    }

    private synchronized Map<String, Object> deviceSelected(String host, String id, Map<String, Object> result) {
        choices.device(id);
        if (hosts.isActive(host)) {
            hosts.deviceName(result);
        }
        publishIfChanged();
        return snapshot();
    }

    private void applyPreferredDevice(String host) {
        if (choices.deviceId() == null) {
            return;
        }
        hosts.ask(host, "audio.selectDevice", Map.of("deviceId", choices.deviceId()))
                .whenComplete((result, failure) -> preferredDeviceApplied(host, result, failure));
    }

    private synchronized void preferredDeviceApplied(String host, Map<String, Object> result, Throwable failure) {
        if (!hosts.isActive(host)) {
            return;
        }
        if (failure != null) {
            log.warn("dispositivo de voz preferido não foi aplicado; o host usa o padrão: {}",
                    HostCapture.message(failure));
            return;
        }
        hosts.deviceName(result);
        publishIfChanged();
    }

    private synchronized void engineChanged() {
        click.engineChanged();
        reconcile();
    }

    /** Pede ao host a captura que o estado efetivo exige, se ainda não pediu. */
    private synchronized void reconcile() {
        String host = hosts.active();
        if (host != null) {
            boolean wanted = captureWanted();
            if (hosts.needsRequest(wanted)) {
                requestCapture(host, wanted);
            }
        }
        syncStream();
        publishIfChanged();
    }

    /** Abre, troca ou fecha o fluxo contínuo conforme o modo em vigor e a captura confirmada. */
    private synchronized void syncStream() {
        stream.sync(hosts.confirmedOn(), choices.desired(), click.active());
    }

    /** Com motor para ouvir, ou durante o teste pedido pelo usuário (SPEC-009 §5). */
    private boolean captureWanted() {
        if (!hosts.connected()) {
            return false;
        }
        return choices.testing()
                || click.capturing()
                || ((choices.desired() == VoiceMode.WAKE || choices.desired() == VoiceMode.OPEN) && stream.ready());
    }

    private void requestCapture(String host, boolean enabled) {
        long request = hosts.pending(enabled);
        publishIfChanged();
        hosts.send(host, enabled).whenComplete((result, failure) -> captureAnswered(request, enabled, result, failure));
    }

    private synchronized void captureAnswered(
            long request, boolean enabled, Map<String, Object> result, Throwable failure) {
        if (!hosts.current(request)) {
            return;
        }
        String disagreement = hosts.answered(enabled, result, failure);
        if (disagreement != null && enabled && choices.testing()) {
            choices.finishTest(disagreement);
            reconcile();
        }
        syncStream();
        publishIfChanged();
    }

    private boolean testing() {
        return choices.testing();
    }

    private boolean clickActive() {
        return click.active();
    }

    private Map<String, Object> snapshot() {
        return VoiceSnapshot.of(choices, hosts, click, stream, commands);
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
                hosts.captureName(),
                snapshot.get("reason") == null ? "" : " — " + snapshot.get("reason"));
        publisher.accept(snapshot);
    }
}
