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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.SessionId;
import zordon.api.event.EventType;
import zordon.api.zwp.EndpointFile;
import zordon.core.endpoint.EndpointPublisher;
import zordon.core.permission.OppressorPhrase;
import zordon.core.platform.SystemdNotifier;

/**
 * A subida do núcleo: métodos registrados, subsistemas de pé, porta escutando,
 * endpoint publicado e, só então, o que roda em segundo plano.
 */
final class CoreStartup {

    private static final Logger log = LoggerFactory.getLogger(ZordonCore.class);
    private static final Duration LISTEN_TIMEOUT = Duration.ofSeconds(10);

    private final CoreModules modules;
    private final SystemdNotifier systemd;
    private final String startId;
    private final Instant startedAt;
    private final String token;
    private SessionId voiceSession;

    CoreStartup(CoreModules modules, SystemdNotifier systemd, String startId, Instant startedAt, String token) {
        this.modules = modules;
        this.systemd = systemd;
        this.startId = startId;
        this.startedAt = startedAt;
        this.token = token;
    }

    void start() throws InterruptedException {
        // Os métodos que o protocolo expõe. Nada aqui bloqueia.
        SystemRegistrar.register(modules, version(), startedAt);
        FeatureRegistrar.register(modules);
        startSubsystems();
        listenAndAnnounce();
        startBackground();
    }

    /** Auditoria conferida, trace, narrador e voz de pé — antes de aceitar conexão. */
    private void startSubsystems() {
        modules.trust().check().verify(modules.defense().defense());
        modules.voice().start(this::heard);
        CoreEventForwarder.forward(modules.base().bus(), modules.base().server());
    }

    /** Escuta, publica o endpoint e só então se declara pronto ao systemd. */
    private void listenAndAnnounce() throws InterruptedException {
        modules.base().server().start();
        if (!modules.base().server().awaitListening(LISTEN_TIMEOUT)) {
            throw new IllegalStateException("ZWP não começou a escutar em " + LISTEN_TIMEOUT);
        }

        // A porta real, não a configurada: com porta 0 o sistema escolhe uma, e é
        // essa que precisa ir para o endpoint.json.
        EndpointFile endpoint = new EndpointPublisher(modules.base().config())
                .publish(startId, startedAt, token, modules.base().server().getPort());

        // Só agora o serviço se declara pronto: com a porta aceitando conexões e o
        // endereço publicado. É o que Type=notify compra (docs/operations/install.md §3).
        systemd.status("ZWP em " + endpoint.endpoints().getFirst());
        systemd.ready();

        // Os servidores MCP conectam em segundo plano: o núcleo já está pronto (SPEC-020 CA-1).
    }

    /**
     * Uma fala da escuta: ou mexe no OPPRESSOR MODE, ou vira turno (SPEC-036 CA-9).
     *
     * <p>A checagem vem antes do turno porque um pedido de modo não pode virar
     * conversa: passar pelo modelo tornaria não-determinístico algo que decide
     * se o motor de permissão continua no caminho.
     */
    private void heard(String text) {
        switch (OppressorPhrase.of(text)) {
            // A voz só abre o pedido de senha na tela; nunca autoriza (ADR-0030).
            case ENTER -> modules.base().bus().publish(EventType.OPPRESSOR_PROMPT, Map.of("heard", text));
            case EXIT -> modules.trust().oppressor().exit("voice");
            case NONE -> modules.base().turns().send(voiceConversation(), text, "voice");
        }
    }

    /** O que sobe depois de pronto: MCP, destilação, monitor e as tarefas interrompidas. */
    private void startBackground() {
        modules.defense().mcp().start();
        modules.memory().distiller().start();
        modules.monitor().start();
        InterruptedTasks.announce(modules.tasks().tasks().recover(), modules.trust().notifications());
        modules.automation().automations().start();
        modules.defense().integrity().start();
        modules.tasks().usage().start();
        modules.defense().hostWatch().start();
        modules.base().bus().publish(EventType.CORE_STARTED, Map.of("startId", startId, "version", version()));
        log.info("Zordon {} pronto — startId {}", version(), startId);
    }

    /** A conversa das perguntas por voz: uma só, criada na primeira. */
    private synchronized SessionId voiceConversation() {
        if (voiceSession == null || !modules.base().conversations().exists(voiceSession)) {
            voiceSession = modules.base().conversations().newSession("Conversa por voz");
        }
        return voiceSession;
    }

    private static String version() {
        return Optional.ofNullable(ZordonCore.class.getPackage().getImplementationVersion())
                .orElse("0.0.0-dev");
    }
}
