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
package zordon.core.tools;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;
import zordon.core.zwp.ClientRequests;
import zordon.security.Gatekeeper;

/**
 * O lado do núcleo da ponte com o Windows (SPEC-016, Interfaces §6). Nenhuma
 * string de shell atravessa: o núcleo só pede para abrir um item do catálogo que
 * o próprio host listou.
 */
@Spec("SPEC-016")
public final class WindowsBridge {

    public static final String CAPABILITY = "windows.apps";

    /** Um aplicativo do Menu Iniciar, pelo id que o host deu. */
    public record App(String id, String name) {}

    private static final Logger log = LoggerFactory.getLogger(WindowsBridge.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ClientRequests clients;
    private final Deque<String> hosts = new ArrayDeque<>();
    private volatile List<App> catalog = List.of();

    public WindowsBridge(ClientRequests clients) {
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    public synchronized void hostConnected(String sessionId) {
        hosts.remove(sessionId);
        hosts.addLast(sessionId);
        refresh();
    }

    public synchronized void hostDisconnected(String sessionId) {
        hosts.remove(sessionId);
    }

    private synchronized Optional<String> host() {
        return Optional.ofNullable(hosts.peekLast());
    }

    /** Pede o catálogo ao host; o anterior vale até a resposta chegar. */
    public void refresh() {
        host().ifPresent(host -> clients.request(host, "windows.apps", Map.of(), TIMEOUT).thenAccept(result -> {
            if (result.get("apps") instanceof List<?> apps) {
                catalog = apps.stream().filter(Map.class::isInstance).map(app -> (Map<?, ?>) app)
                        .map(app -> new App(String.valueOf(app.get("id")), String.valueOf(app.get("name"))))
                        .toList();
                log.info("catálogo do Windows: {} aplicativos", catalog.size());
            }
        }).exceptionally(failure -> {
            log.warn("catálogo do Windows indisponível: {}", failure.getMessage());
            return null;
        }));
    }

    public List<App> catalog() {
        return catalog;
    }

    /**
     * O aplicativo que melhor casa com o nome falado: igual, depois começa com,
     * depois contém; entre empates, o nome mais curto ("IntelliJ IDEA" antes de
     * "IntelliJ IDEA — desinstalar").
     */
    public Optional<App> find(String spoken) {
        String wanted = key(spoken);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        Comparator<App> shortest = Comparator.comparingInt(app -> app.name().length());
        for (java.util.function.Predicate<String> rule : List.<java.util.function.Predicate<String>>of(
                name -> name.equals(wanted), name -> name.startsWith(wanted), name -> name.contains(wanted))) {
            Optional<App> hit = catalog.stream().filter(app -> rule.test(key(app.name()))).min(shortest);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    public Map<String, Object> open(App app) throws Exception {
        String host = host().orElseThrow(() -> new ToolException(
                "o host do Windows não está conectado; não consigo abrir aplicativos"));
        return clients.request(host, "windows.openApp", Map.of("id", app.id()), TIMEOUT)
                .get(TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
    }

    /** Sem host, a fila durável continua sendo a fonte de entrega. */
    public void notify(String title, String body, String severity) {
        host().ifPresent(host -> clients.request(host, "windows.notify",
                Map.of("title", title, "body", body, "severity", severity), TIMEOUT)
                .exceptionally(error -> {
                    log.debug("aviso nativo indisponível: {}", error.getMessage());
                    return Map.of();
                }));
    }

    static String key(String text) {
        String plain = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.replaceAll("[^a-z0-9]", "");
    }

    /** {@code app.open}: GREEN, porque o alvo vem do catálogo, não de um caminho qualquer (Segurança §2). */
    public Tool openTool() {
        return new Tool() {
            @Override public String name() { return "app.open"; }
            @Override public String description() { return "Abre um aplicativo do Menu Iniciar do Windows."; }
            @Override public Map<String, Object> inputSchema() {
                return Tool.schema("name", "nome do aplicativo, como aparece no Menu Iniciar");
            }
            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(Effect.SPAWN_PROCESS); }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                String spoken = Tool.text(args, "name");
                if (host().isEmpty()) {
                    throw new ToolException("o host do Windows não está conectado; não consigo abrir aplicativos");
                }
                App app = find(spoken).orElseThrow(() -> new ToolException(
                        "não encontrei um aplicativo chamado " + spoken));
                return new ActionDescriptor(name(), Map.of("id", app.id(), "name", app.name()), baseRisk(), effects(),
                        List.of(), 1, List.of(), "Abrir " + app.name());
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                App app = new App(String.valueOf(permit.action().args().get("id")),
                        String.valueOf(permit.action().args().get("name")));
                Map<String, Object> result = open(app);
                if (!Boolean.TRUE.equals(result.get("opened"))) {
                    throw new ToolException("o Windows não abriu " + app.name()
                            + (result.get("reason") instanceof String reason ? ": " + reason : ""));
                }
                return ToolResult.of("Abrindo " + app.name() + ".");
            }
        };
    }
}
