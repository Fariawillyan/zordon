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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.trace.Spec;
import zordon.security.ProcessRunner;

/**
 * Um servidor MCP pelo transporte {@code stdio}: JSON-RPC 2.0, uma mensagem por
 * linha (SPEC-020). Todo pedido tem prazo; uma resposta que nunca vem não trava
 * ninguém.
 */
@Spec("SPEC-020")
public final class McpClient implements AutoCloseable {

    /** Uma linha maior que isto é cortada: o servidor não enche a memória do núcleo. */
    public static final int MAX_LINE = 1024 * 1024;
    /** Três mensagens seguidas que não são JSON derrubam a conexão (SPEC-020 §13). */
    static final int MAX_INVALID = 3;
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final String name;
    private final ProcessRunner.Live live;
    private final OutputStream out;
    private final Map<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final Runnable onClosed;
    private volatile boolean closed;
    private int invalid;

    public McpClient(String name, ProcessRunner.Live live, Runnable onClosed) {
        this.name = Objects.requireNonNull(name, "name");
        this.live = Objects.requireNonNull(live, "live");
        this.out = live.stdin();
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
        Thread.ofVirtual().name("mcp-" + name).start(this::read);
    }

    public CompletableFuture<Map<String, Object>> request(String method, Map<String, Object> params, Duration timeout) {
        long id = ids.incrementAndGet();
        CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
        pending.put(id, answer);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        try {
            send(message);
        } catch (IOException e) {
            pending.remove(id);
            return CompletableFuture.failedFuture(e);
        }
        return answer.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((result, failure) -> pending.remove(id));
    }

    public void notify(String method, Map<String, Object> params) throws IOException {
        send(Map.of("jsonrpc", "2.0", "method", method, "params", params));
    }

    private synchronized void send(Map<String, Object> message) throws IOException {
        if (closed) {
            throw new IOException("o servidor MCP " + name + " está fechado");
        }
        out.write(json.writeValueAsBytes(message));
        out.write('\n');
        out.flush();
    }

    private void read() {
        try (Reader in = new InputStreamReader(live.stdout(), StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            boolean oversized = false;
            int c;
            while ((c = in.read()) != -1) {
                if (c != '\n') {
                    if (line.length() < MAX_LINE) {
                        line.append((char) c);
                    } else {
                        oversized = true;   // o resto da linha é descartado sem ir para a memória
                    }
                    continue;
                }
                if (oversized) {
                    tooLarge(line);
                } else if (!line.isEmpty() && !line.toString().isBlank()) {
                    dispatch(line.toString());
                }
                line.setLength(0);
                oversized = false;
                if (invalid >= MAX_INVALID) {
                    log.warn("MCP {}: {} mensagens seguidas que não são JSON; conexão encerrada", name, invalid);
                    break;
                }
            }
        } catch (IOException e) {
            log.debug("MCP {}: leitura encerrada: {}", name, e.getMessage());
        } finally {
            closed = true;
            if (invalid >= MAX_INVALID) {
                live.close();
            }
            IOException gone = new IOException("o servidor MCP " + name + " encerrou");
            pending.values().forEach(answer -> answer.completeExceptionally(gone));
            pending.clear();
            onClosed.run();
        }
    }

    /** A resposta passou de 1 MB: falha o pedido dela em vez de esperar o prazo. */
    private void tooLarge(CharSequence start) {
        log.warn("MCP {}: mensagem maior que 1 MB descartada", name);
        Matcher id = ID.matcher(start.subSequence(0, Math.min(start.length(), 256)));
        if (id.find()) {
            CompletableFuture<Map<String, Object>> answer = pending.get(Long.parseLong(id.group(1)));
            if (answer != null) {
                answer.completeExceptionally(new IOException("resposta do servidor MCP " + name + " maior que 1 MB"));
            }
        }
    }

    private void dispatch(String line) {
        Map<String, Object> message;
        try {
            message = json.readValue(line, new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            invalid++;
            log.warn("MCP {}: linha que não é JSON ignorada", name);
            return;
        }
        invalid = 0;
        if (!(message.get("id") instanceof Number number) || message.containsKey("method")) {
            return;   // notificação ou pedido do servidor: o cliente do M4 não atende pedidos
        }
        CompletableFuture<Map<String, Object>> answer = pending.get(number.longValue());
        if (answer == null) {
            return;
        }
        if (message.get("error") instanceof Map<?, ?> error) {
            answer.completeExceptionally(new IOException("MCP " + name + ": " + error.get("message")));
        } else if (message.get("result") instanceof Map<?, ?> result) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) result;
            answer.complete(typed);
        } else {
            answer.complete(Map.of());
        }
    }

    public boolean alive() {
        return !closed && live.alive();
    }

    @Override
    public void close() {
        closed = true;
        live.close();
    }
}
