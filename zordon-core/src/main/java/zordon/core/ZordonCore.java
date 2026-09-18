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

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventType;
import zordon.api.event.Topic;
import zordon.api.zwp.EndpointFile;
import zordon.api.zwp.ZwpCloseCode;
import zordon.core.endpoint.EndpointPublisher;
import zordon.core.event.QueuePolicy;
import zordon.core.event.ZordonEventBus;
import zordon.core.platform.SystemdNotifier;
import zordon.ai.Pricing;
import zordon.ai.registry.AiSettings;
import zordon.ai.registry.ProviderRegistry;
import zordon.core.chat.ConversationStore;
import zordon.core.chat.IntentRouter;
import zordon.core.chat.PromptComposer;
import zordon.core.chat.TurnManager;
import zordon.core.zwp.ChatMethods;
import zordon.core.zwp.SessionMethods;
import zordon.core.zwp.ZwpServer;
import zordon.api.trace.Spec;

/**
 * Composition root do núcleo: monta os componentes, sobe o ZWP e publica o
 * endpoint.
 *
 * <p>Injeção é manual e explícita. Um container de DI aqui adicionaria tempo de
 * inicialização e magia de classpath sem resolver nenhum problema que este projeto
 * tenha (docs/architecture/components.md §6).
 */
@Spec("SPEC-002")
public final class ZordonCore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZordonCore.class);
    private static final Duration LISTEN_TIMEOUT = Duration.ofSeconds(10);

    /** O que este núcleo sabe fazer. Cresce por marco, e nunca antes de ser verdade. */
    private static final List<String> CAPABILITIES = List.of("chat.stream");

    private final ZordonConfig config;
    private final SystemdNotifier systemd;
    private final ZordonEventBus bus;
    private final ZwpServer server;
    private final ConversationStore conversations = new ConversationStore();
    private final TurnManager turns;
    private final String startId;
    private final Instant startedAt;
    private final String token;

    public ZordonCore(ZordonConfig config, SystemdNotifier systemd) {
        this(config, systemd, System.getenv());
    }

    /**
     * @param environment de onde as referências {@code env:} do {@code config.toml}
     *     tiram as chaves. Injetado para que um teste não use, sem querer, a chave
     *     de quem o está rodando.
     */
    public ZordonCore(ZordonConfig config, SystemdNotifier systemd, Map<String, String> environment) {
        this.config = config;
        this.systemd = systemd;
        this.startId = StartId.generate();
        this.startedAt = Instant.now();
        this.token = EndpointPublisher.newToken();
        this.bus = new ZordonEventBus(startId);
        this.server = new ZwpServer(new InetSocketAddress(config.bindAddress(), config.port()), token);
        this.turns = new TurnManager(
                bus,
                conversations,
                new IntentRouter(),
                new PromptComposer(),
                ProviderRegistry.build(
                        AiSettings.load(config.home().resolve("config.toml")),
                        Pricing.load(config.home().resolve("pricing.toml")),
                        environment));
    }

    public static void main(String[] args) throws Exception {
        ZordonCore core = new ZordonCore(ZordonConfig.fromEnvironment(), new SystemdNotifier());
        Runtime.getRuntime().addShutdownHook(new Thread(core::close, "zordon-shutdown"));
        core.start();
        Thread.currentThread().join();
    }

    public void start() throws InterruptedException {
        new SessionMethods(bus, version(), startedAt, CAPABILITIES).registerOn(server);
        new ChatMethods(turns, conversations).registerOn(server);
        forwardEventsToClients();

        server.start();
        if (!server.awaitListening(LISTEN_TIMEOUT)) {
            throw new IllegalStateException("ZWP não começou a escutar em " + LISTEN_TIMEOUT);
        }

        // A porta real, não a configurada: com porta 0 o sistema escolhe uma, e é
        // essa que precisa ir para o endpoint.json.
        EndpointFile endpoint = new EndpointPublisher(config).publish(startId, startedAt, token, port());

        // Só agora o serviço se declara pronto: com a porta aceitando conexões e o
        // endereço publicado. É o que Type=notify compra (docs/operations/install.md §3).
        systemd.status("ZWP em " + endpoint.endpoints().getFirst());
        systemd.ready();

        bus.publish(EventType.CORE_STARTED, Map.of("startId", startId, "version", version()));
        log.info("Zordon {} pronto — startId {}", version(), startId);
    }

    public int port() {
        return server.getPort();
    }

    public ZordonEventBus events() {
        return bus;
    }

    @Override
    public void close() {
        log.info("encerrando o núcleo");
        systemd.stopping();
        try {
            server.stop(ZwpCloseCode.SHUTTING_DOWN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        bus.close();
        // O endpoint.json fica onde está: o Zordon não apaga arquivos (ADR-0015).
        // O cliente descobre que o núcleo saiu pela conexão, não pela ausência do
        // arquivo, e o token deste boot deixa de valer no próximo.
    }

    /**
     * Duas assinaturas, porque as políticas de fila diferem: tópicos obrigatórios
     * falham alto em vez de descartar em silêncio (ADR-0011).
     */
    private void forwardEventsToClients() {
        Set<String> droppable = Topic.ALL.stream()
                .filter(topic -> !Topic.MANDATORY.contains(topic))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        bus.subscribe("zwp-mandatory", Topic.MANDATORY, QueuePolicy.rejectPublish(512), server::broadcastEvent);
        bus.subscribe("zwp", droppable, QueuePolicy.dropOldest(1_024), server::broadcastEvent);
    }

    private String version() {
        return Optional.ofNullable(ZordonCore.class.getPackage().getImplementationVersion())
                .orElse("0.0.0-dev");
    }
}
