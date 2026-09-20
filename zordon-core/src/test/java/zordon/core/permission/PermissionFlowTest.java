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
package zordon.core.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;
import zordon.api.security.ZPath;
import zordon.api.trace.AcceptanceCriteria;
import zordon.core.zwp.ClientRequests;
import zordon.security.CommandValidator;
import zordon.security.DefaultPermissionEngine;
import zordon.security.PathPolicy;
import zordon.security.PermissionEngine;
import zordon.security.Redactor;

/** Pedido de permissão ao desktop e kill switch (SPEC-015). */
class PermissionFlowTest {

    @TempDir
    Path home;

    /** Registra os pedidos e responde com o que o teste mandar. */
    static final class Desktop implements ClientRequests {
        record Call(String session, String method, Map<String, Object> params) {}

        final List<Call> calls = new CopyOnWriteArrayList<>();
        volatile CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();

        @Override
        public CompletableFuture<Map<String, Object>> request(String sessionId, String method,
                Map<String, Object> params, Duration timeout) {
            calls.add(new Call(sessionId, method, params));
            return answer;
        }
    }

    private static ActionDescriptor quarantine(String home) {
        return new ActionDescriptor("fs.quarantine", Map.of(), RiskLevel.RED, Set.of(Effect.QUARANTINE_FS),
                List.of(ZPath.ofWsl(home + "/dev/app/logs/a.log"), ZPath.ofWsl(home + "/dev/app/logs/b.log")), 43,
                List.of(), "Mover 43 arquivos de ~/dev/app/logs para a quarentena");
    }

    private DefaultPermissionEngine engine(DesktopApprover approver) {
        return new DefaultPermissionEngine(PathPolicy.defaults(home.toString(), List.of("~/dev"), List.of("~")),
                new CommandValidator(Map.of()), new Redactor(), () -> approver);
    }

    @AcceptanceCriteria("SPEC-015/CA-1")
    @Test
    void oDesktopMaisRecenteRecebeOPedidoComResumoRiscoOrigemEAlvos() throws Exception {
        Desktop desktop = new Desktop();
        DesktopApprover approver = new DesktopApprover(desktop);
        DefaultPermissionEngine engine = engine(approver);
        Principal voice = Principal.user(RequestOrigin.VOICE);
        ActionDescriptor action = quarantine(home.toString());
        Decision.AskUser ask = (Decision.AskUser) engine.evaluate(action, voice,
                PermissionEngine.PolicyContext.interactive());

        // Sem desktop: nega na hora, com o motivo.
        assertThat(engine.requestApproval(action, voice, ask).get(1, TimeUnit.SECONDS))
                .isInstanceOf(Decision.Deny.class).extracting(Decision::reason).asString()
                .contains("nenhum desktop");

        approver.desktopConnected("d1");
        approver.desktopConnected("d2");
        CompletableFuture<Decision> pending = engine.requestApproval(action, voice, ask);
        Desktop.Call call = desktop.calls.getLast();
        assertThat(call.session()).isEqualTo("d2");
        assertThat(call.method()).isEqualTo("ui.requestPermission");
        assertThat(call.params()).containsEntry("summary", "Mover 43 arquivos de ~/dev/app/logs para a quarentena")
                .containsEntry("risk", "red").containsEntry("origin", "voice").containsEntry("perAction", true)
                .containsEntry("targetCount", 43).containsEntry("ttlMs", 60_000L);
        assertThat((List<?>) call.params().get("targets")).hasSize(2);

        desktop.answer.complete(Map.of("approval", "once"));
        assertThat(pending.get(1, TimeUnit.SECONDS)).isInstanceOf(Decision.Allow.class);

        // Queda do desktop no meio do pedido: nega.
        desktop.answer = new CompletableFuture<>();
        CompletableFuture<Decision> dropped = engine.requestApproval(action, voice, ask);
        desktop.answer.completeExceptionally(new IllegalStateException("sessão encerrada"));
        assertThat(dropped.get(1, TimeUnit.SECONDS)).isInstanceOf(Decision.Deny.class);

        approver.desktopDisconnected("d1");
        approver.desktopDisconnected("d2");
        assertThat(approver.available()).isFalse();
    }

    @AcceptanceCriteria("SPEC-015/CA-6")
    @Test
    void pausarSobreviveAoReinicioENegaTudoAcimaDeGreen() throws Exception {
        Path state = home.resolve("state/lockdown.json");
        List<String> events = new CopyOnWriteArrayList<>();
        LockdownService lockdown = new LockdownService(state, Clock.systemUTC(),
                (entered, payload) -> events.add((entered ? "entrou " : "saiu ") + payload));
        lockdown.enter("parece estranho", "desktop");
        assertThat(lockdown.active()).isTrue();

        LockdownService reopened = new LockdownService(state, Clock.systemUTC(), (entered, payload) -> { });
        assertThat(reopened.active()).as("reiniciar não sai do lockdown").isTrue();
        assertThat(reopened.status()).containsEntry("reason", "parece estranho");

        PermissionEngine.PolicyContext ctx = new PermissionEngine.PolicyContext(reopened.active(), false, false, true,
                false, Set.of());
        assertThat(engine(null).evaluate(quarantine(home.toString()), Principal.user(RequestOrigin.UI), ctx))
                .isInstanceOf(Decision.Deny.class).extracting(Decision::reason).asString().contains("lockdown");

        reopened.resume();
        assertThat(new LockdownService(state, Clock.systemUTC(), (e, p) -> { }).active()).isFalse();
        assertThat(events).singleElement().asString().startsWith("entrou");

        // Arquivo ilegível: na dúvida, começa em só leitura.
        Files.writeString(state, "{ não é json");
        assertThat(new LockdownService(state, Clock.systemUTC(), (e, p) -> { }).active()).isTrue();
    }
}
