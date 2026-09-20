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
package zordon.core.zwp;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import zordon.ai.registry.ProviderRegistry;
import zordon.api.trace.Spec;
import zordon.core.chat.TurnManager;
import zordon.core.event.ZordonEventBus;

/**
 * {@code system.diagnostics}: o que a tela de Diagnóstico precisa para responder
 * "o núcleo está bem, e quem vai responder?" (SPEC-005).
 *
 * <p>Só leitura, e só nomes: o estado de um provider pode dizer "defina
 * OPENAI_API_KEY", nunca o valor da chave.
 */
@Spec("SPEC-005")
public final class SystemMethods {

    private final String version;
    private final Instant startedAt;
    private final ZordonEventBus bus;
    private final TurnManager turns;
    private final ProviderRegistry providers;
    private final IntSupplier connectedClients;
    private final Supplier<Map<String, Object>> voice;
    private final Supplier<Map<String, Object>> trace;
    private final Supplier<Map<String, Object>> security;

    public SystemMethods(
            String version,
            Instant startedAt,
            ZordonEventBus bus,
            TurnManager turns,
            ProviderRegistry providers,
            IntSupplier connectedClients,
            Supplier<Map<String, Object>> voice,
            Supplier<Map<String, Object>> trace,
            Supplier<Map<String, Object>> security) {
        this.version = version;
        this.startedAt = startedAt;
        this.bus = bus;
        this.turns = turns;
        this.providers = providers;
        this.connectedClients = connectedClients;
        this.voice = voice;
        this.trace = trace;
        this.security = security;
    }

    private final Map<String, Supplier<Map<String, Object>>> extra = new java.util.concurrent.ConcurrentHashMap<>();

    /** Uma seção a mais do diagnóstico: {@code memory} (SPEC-021), {@code monitor} (SPEC-024)… */
    public SystemMethods with(String section, Supplier<Map<String, Object>> stats) {
        extra.put(java.util.Objects.requireNonNull(section, "section"), java.util.Objects.requireNonNull(stats, "stats"));
        return this;
    }

    public void registerOn(ZwpServer server) {
        server.register("system.diagnostics", this::diagnostics);
    }

    private Map<String, Object> diagnostics(ZwpSession session, Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("core", Map.of(
                "version", version,
                "startId", bus.startId(),
                "startedAt", startedAt.toString(),
                "uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds()));
        result.put("events", Map.of("lastSeq", bus.lastSeq()));
        result.put("clients", connectedClients.getAsInt());
        result.put("turns", Map.of("active", turns.activeTurns().size()));
        result.put("providers", providers.describe());
        result.put("roles", providers.describeRoles());
        result.put("voice", voice.get());
        result.put("trace", trace.get());
        result.put("security", security.get());
        new java.util.TreeMap<>(extra).forEach((section, stats) -> result.put(section, stats.get()));
        return result;
    }
}
