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
package zordon.core.mcp;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;
import zordon.core.tools.Tool;
import zordon.core.tools.ToolException;
import zordon.core.tools.ToolResult;
import zordon.security.Gatekeeper;

/**
 * Uma ferramenta de servidor MCP vista como qualquer outra (SPEC-020). O risco
 * parte do piso da nossa configuração; as anotações do servidor só sobem.
 */
@Spec("SPEC-020")
final class McpTool implements Tool {

    static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private final String server;
    private final String remoteName;
    private final String description;
    private final Map<String, Object> schema;
    private final RiskLevel risk;
    private final Set<Effect> effects;
    private final Supplier<McpClient> client;
    private final Breaker breaker;
    private final Duration timeout;

    McpTool(String server, Map<String, Object> declared, RiskLevel floor, boolean newTool, Supplier<McpClient> client,
            Breaker breaker, Duration timeout) {
        this.server = server;
        this.remoteName = String.valueOf(declared.get("name"));
        String text = declared.get("description") instanceof String given ? given.strip() : "";
        this.description = "[MCP " + server + "] " + (text.length() > 400 ? text.substring(0, 400) + "…" : text);
        @SuppressWarnings("unchecked")
        Map<String, Object> input = declared.get("inputSchema") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of("type", "object", "properties", Map.of());
        this.schema = input;
        Map<?, ?> hints = declared.get("annotations") instanceof Map<?, ?> map ? map : Map.of();
        RiskLevel level = floor;
        EnumSet<Effect> declaredEffects = EnumSet.of(Effect.READ_FS);   // o resultado é conteúdo externo
        if (!Boolean.TRUE.equals(hints.get("readOnlyHint"))) {
            declaredEffects.add(Effect.MODIFY_SYSTEM);
            level = level.atLeast(RiskLevel.YELLOW);
        }
        if (Boolean.TRUE.equals(hints.get("destructiveHint"))) {
            level = RiskLevel.RED;
        }
        if (!Boolean.FALSE.equals(hints.get("openWorldHint"))) {
            declaredEffects.add(Effect.NETWORK);
        }
        // Primeira semana de uma ferramenta: um nível acima, até o usuário ver o que ela faz (MCP §3).
        this.risk = newTool ? level.raise() : level;
        this.effects = Set.copyOf(declaredEffects);
        this.client = client;
        this.breaker = breaker;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return "mcp." + server + "." + remoteName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public RiskLevel baseRisk() {
        return risk;
    }

    @Override
    public Set<Effect> effects() {
        return effects;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return schema;
    }

    @Override
    public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
        if (breaker.open()) {
            throw new ToolException("o servidor MCP " + server + " está em pausa depois de falhas seguidas");
        }
        return new ActionDescriptor(name(), args, risk, effects, List.of(), 1, List.of(),
                "Usar " + remoteName + " do servidor MCP " + server);
    }

    @Override
    public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
        McpClient current = client.get();
        if (current == null || !current.alive()) {
            throw new ToolException("o servidor MCP " + server + " não está conectado");
        }
        Map<String, Object> result;
        try {
            result = current.request("tools/call", Map.of("name", remoteName, "arguments", args), timeout)
                    .get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            breaker.failure();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ToolException(cause instanceof TimeoutException || e instanceof TimeoutException
                    ? "o servidor MCP " + server + " não respondeu em " + timeout.toSeconds() + " s" : String.valueOf(cause.getMessage()));
        }
        StringBuilder text = new StringBuilder();
        if (result.get("content") instanceof List<?> content) {
            for (Object block : content) {
                if (block instanceof Map<?, ?> item && "text".equals(item.get("type"))) {
                    text.append(item.get("text")).append('\n');
                }
            }
        }
        String out = text.toString().strip();
        if (Boolean.TRUE.equals(result.get("isError"))) {
            breaker.failure();
            throw new ToolException("o servidor MCP respondeu com erro: " + out);
        }
        breaker.success();
        return new ToolResult(out.isEmpty() ? "Feito." : out, Map.of());
    }

    /** 5 falhas em 60 s abrem por 5 min (MCP §6). */
    static final class Breaker {
        static final int FAILURES = 5;
        static final Duration WINDOW = Duration.ofSeconds(60);
        static final Duration OPEN_FOR = Duration.ofMinutes(5);

        private final java.util.function.LongSupplier nanos;
        private final java.util.ArrayDeque<Long> failures = new java.util.ArrayDeque<>();
        private long openUntil;

        Breaker(java.util.function.LongSupplier nanos) {
            this.nanos = nanos;
        }

        synchronized boolean open() {
            return openUntil != 0 && nanos.getAsLong() - openUntil < 0;
        }

        synchronized void failure() {
            long now = nanos.getAsLong();
            failures.addLast(now);
            while (!failures.isEmpty() && now - failures.peekFirst() > WINDOW.toNanos()) {
                failures.removeFirst();
            }
            if (failures.size() >= FAILURES) {
                openUntil = now + OPEN_FOR.toNanos();
                failures.clear();
            }
        }

        synchronized void success() {
            failures.clear();
        }
    }
}
