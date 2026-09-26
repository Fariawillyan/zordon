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
import java.util.Map;
import zordon.ai.Pricing;
import zordon.ai.registry.AiSettings;
import zordon.ai.registry.ProviderRegistry;
import zordon.api.zwp.ZwpCloseCode;
import zordon.core.chat.ConversationStore;
import zordon.core.chat.IntentRouter;
import zordon.core.chat.PromptComposer;
import zordon.core.chat.TurnManager;
import zordon.core.event.ZordonEventBus;
import zordon.core.zwp.ZwpServer;
import zordon.security.Redactor;

/** A base do núcleo: configuração, barramento, ZWP, providers e o turno. Os subsistemas são montados em cima dela. */
record CoreBase(ZordonConfig config, Map<String, String> environment, ZordonEventBus bus, ZwpServer server,
        ProviderRegistry providers, ConversationStore conversations, IntentRouter router, PromptComposer prompts,
        TurnManager turns, SecuritySettingsLoader settings, DeferredCliRunner cli, Redactor redactor) {

    /**
     * @param environment de onde as referências {@code env:} do {@code config.toml}
     *     tiram as chaves. Injetado para que um teste não use, sem querer, a chave
     *     de quem o está rodando.
     */
    static CoreBase create(ZordonConfig config, Map<String, String> environment, String startId, String token) {
        SecuritySettingsLoader settings = new SecuritySettingsLoader(config.home().resolve("config.toml"), environment);
        ZordonEventBus bus = new ZordonEventBus(startId);
        ZwpServer server = new ZwpServer(new InetSocketAddress(config.bindAddress(), config.port()), token);
        DeferredCliRunner cli = new DeferredCliRunner(settings);
        ProviderRegistry providers = ProviderRegistry.build(
                AiSettings.load(config.home().resolve("config.toml")),
                Pricing.load(config.home().resolve("pricing.toml")),
                environment,
                // O provider por assinatura roda pelo caminho mediado, montado mais abaixo (SPEC-018).
                cli);
        ConversationStore conversations = new ConversationStore();
        IntentRouter router = new IntentRouter();
        PromptComposer prompts = new PromptComposer();
        TurnManager turns = new TurnManager(bus, conversations, router, prompts, providers);
        return new CoreBase(config, environment, bus, server, providers, conversations, router, prompts, turns, settings,
                cli, new Redactor());
    }

    /** Fecha a porta avisando os clientes de que o núcleo está saindo. */
    void stopServer() {
        try {
            server.stop(ZwpCloseCode.SHUTTING_DOWN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
