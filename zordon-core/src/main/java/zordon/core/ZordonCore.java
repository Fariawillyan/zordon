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

import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.core.endpoint.EndpointPublisher;
import zordon.core.event.ZordonEventBus;
import zordon.core.platform.SystemdNotifier;

/**
 * Composition root do núcleo: monta os componentes, sobe o ZWP e publica o
 * endpoint.
 *
 * <p>Injeção é manual e explícita. Um container de DI aqui adicionaria tempo de
 * inicialização e magia de classpath sem resolver nenhum problema que este projeto
 * tenha (docs/architecture/components.md §6).
 *
 * <p>A montagem é por subsistema: a base em {@link CoreBase}, os subsistemas em
 * {@link CoreModules}, na ordem em que um depende do outro, e a subida em fases
 * em {@link CoreStartup}.
 */
@Spec("SPEC-002")
public final class ZordonCore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZordonCore.class);

    private final SystemdNotifier systemd;
    private final CoreBase base;
    private final CoreModules modules;
    private final CoreStartup startup;

    public ZordonCore(ZordonConfig config, SystemdNotifier systemd) {
        this(config, systemd, System.getenv());
    }

    /**
     * @param environment de onde as referências {@code env:} do {@code config.toml}
     *     tiram as chaves. Injetado para que um teste não use, sem querer, a chave
     *     de quem o está rodando.
     */
    public ZordonCore(ZordonConfig config, SystemdNotifier systemd, Map<String, String> environment) {
        this.systemd = systemd;
        String startId = StartId.generate();
        Instant startedAt = Instant.now();
        String token = EndpointPublisher.newToken();
        this.base = CoreBase.create(config, environment, startId, token);
        this.modules = CoreModules.build(base);
        this.startup = new CoreStartup(modules, systemd, startId, startedAt, token);
    }

    public static void main(String[] args) throws Exception {
        ZordonCore core = new ZordonCore(ZordonConfig.fromEnvironment(), new SystemdNotifier());
        Runtime.getRuntime().addShutdownHook(new Thread(core::close, "zordon-shutdown"));
        core.start();
        Thread.currentThread().join();
    }

    public void start() throws InterruptedException {
        startup.start();
    }

    public int port() {
        return base.server().getPort();
    }

    public ZordonEventBus events() {
        return base.bus();
    }

    @Override
    public void close() {
        log.info("encerrando o núcleo");
        systemd.stopping();
        modules.close();
        // O endpoint.json fica onde está: o Zordon não apaga arquivos (ADR-0015).
        // O cliente descobre que o núcleo saiu pela conexão, não pela ausência do
        // arquivo, e o token deste boot deixa de valer no próximo.
    }
}
