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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;
import zordon.core.notify.NotificationCenter;
import zordon.core.tools.SkillRuntime;
import zordon.security.Gatekeeper;
import zordon.security.ProcessRunner;

/**
 * Os servidores MCP declarados, do início ao fim (SPEC-020). Conecta em segundo
 * plano: o núcleo não espera ninguém. Superfície que mudou não entra sem o
 * usuário aprovar na tela.
 *
 * <p>Esta classe é o lock e a API; as conexões pelo nome ficam em
 * {@link McpConnections}, o ciclo de vida em {@link McpLifecycle}, a superfície
 * aprovada em {@link McpSurfaces} e o início do processo em {@link McpLauncher}.
 */
@Spec("SPEC-020")
public final class McpManager implements AutoCloseable {

    /** Um servidor do {@code config.toml}. */
    public record Server(String name, List<String> command, RiskLevel floor, boolean autostart) {}

    public enum State { STOPPED, STARTING, CONNECTED, DRIFT, RECONNECTING, FAILED }

    static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(10);
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60),
            Duration.ofMinutes(5));
    static final Duration NEW_TOOL = Duration.ofDays(7);
    static final String PROTOCOL = "2025-06-18";

    /** Com quem o gerenciador fala para mediar uma chamada MCP. */
    public record Deps(Gatekeeper gatekeeper, ProcessRunner runner, SkillRuntime tools,
            NotificationCenter notifications) {}

    /** Onde o estado dos servidores e o trabalho das chamadas ficam em disco. */
    public record Dirs(Path stateDir, Path workDir) {}

    private final McpConnections connections;

    public McpManager(List<Server> servers, Deps deps, Dirs dirs, Clock clock, LongSupplier nanos) {
        this.connections = new McpConnections(servers, deps, dirs, clock, nanos, this);
    }

    /** {@code [[mcp.server]]} do {@code config.toml}. Entrada inválida é ignorada com aviso. */
    public static List<Server> load(Path configToml) {
        return McpConfig.load(configToml);
    }

    /** {@code SYSTEM_ALERT} de conexão, queda e falha (SPEC-020 §8). */
    public McpManager onAlert(Consumer<Map<String, Object>> listener) {
        connections.lifecycle().onAlert(listener);
        return this;
    }

    /** Para os testes: prazos curtos no lugar de 30 s e de 5 s–5 min. */
    McpManager timings(Duration call, List<Duration> waits) {
        connections.timings(call, waits);
        return this;
    }

    /** Conecta os servidores {@code autostart}, em segundo plano. */
    public void start() {
        connections.start();
    }

    /** Aprovação pela tela de uma superfície nova (SPEC-020 CA-3). */
    public synchronized boolean approve(String name) throws IOException {
        return connections.approve(name);
    }

    /** Desconecta e tira as ferramentas, sem matar o processo do servidor (SPEC-027). */
    public boolean isolate(String name) {
        return connections.isolate(name);
    }

    /** O usuário liberou: volta a conectar. */
    public boolean release(String name) {
        return connections.release(name);
    }

    public List<Map<String, Object>> describe() {
        return connections.describe();
    }

    public State state(String name) {
        return connections.state(name);
    }

    @Override
    public void close() {
        connections.close();
    }
}
