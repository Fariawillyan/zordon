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
package zordon.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;
import zordon.api.trace.AcceptanceCriteria;

/** O desvio do OPPRESSOR MODE no caminho único (SPEC-036 §5). */
class GatekeeperOppressorTest {

    @TempDir
    Path dir;

    private final AtomicBoolean mode = new AtomicBoolean();
    private final Captura audit = new Captura();

    /** Sem catálogo: qualquer comando é desconhecido, o pior caso do motor. */
    private Gatekeeper gatekeeper() {
        return new Gatekeeper(new DefaultPermissionEngine(
                PathPolicy.defaults(dir.toString(), List.of("~"), List.of("~")),
                new CommandValidator(Map.of()), new Redactor(), () -> null), audit, mode::get);
    }

    private static ActionDescriptor perigosa() {
        return new ActionDescriptor("process.run", Map.of("argv", List.of("/tmp/qualquer")), RiskLevel.RED,
                Set.of(Effect.SPAWN_PROCESS, Effect.MODIFY_SYSTEM), List.of(), 1, List.of("/tmp/qualquer"),
                "Rodar algo fora do catálogo");
    }

    private Gatekeeper.Permit autorizar(Gatekeeper gatekeeper, boolean lockdown) throws Exception {
        PermissionEngine.PolicyContext ctx = lockdown
                ? new PermissionEngine.PolicyContext(true, false, false, true, false, Set.of())
                : PermissionEngine.PolicyContext.interactive();
        return gatekeeper.authorize(perigosa(), Principal.user(RequestOrigin.UI), ctx, "t1")
                .get(5, TimeUnit.SECONDS);
    }

    @AcceptanceCriteria("SPEC-036/CA-3")
    @Test
    void comOModoAtivoAAcaoQueSeriaNegadaPassaSemAvaliacao() throws Exception {
        Gatekeeper gatekeeper = gatekeeper();

        // Sem o modo: RED sem tela para autorizar é recusa.
        assertThat(autorizar(gatekeeper, false)).isInstanceOf(Gatekeeper.Permit.Refused.class);

        mode.set(true);
        Gatekeeper.Permit permit = autorizar(gatekeeper, false);
        assertThat(permit).isInstanceOf(Gatekeeper.Permit.Granted.class);
        assertThat(permit.decision()).isInstanceOf(Decision.Allow.class);
        assertThat(permit.decision().reason()).isEqualTo("OPPRESSOR MODE");
        // A auditoria recebe a intenção como em qualquer ação, e distingue quem liberou.
        assertThat(audit.entries).last().extracting(AuditLog.Entry::decidedBy).isEqualTo("oppressor");
    }

    @AcceptanceCriteria("SPEC-036/CA-4")
    @Test
    void oLockdownNaoEhAtravessado() throws Exception {
        Gatekeeper gatekeeper = gatekeeper();
        mode.set(true);

        Gatekeeper.Permit permit = autorizar(gatekeeper, true);

        // O kill switch é a única coisa acima do modo: vale a política de só leitura.
        assertThat(permit).isInstanceOf(Gatekeeper.Permit.Refused.class);
        assertThat(permit.decision().reason()).contains("lockdown");
        assertThat(audit.entries).last().extracting(AuditLog.Entry::decidedBy).isNotEqualTo("oppressor");
    }

    @AcceptanceCriteria("SPEC-036/CA-6")
    @Test
    void sairDoModoVoltaAoNormalNaAcaoSeguinte() throws Exception {
        Gatekeeper gatekeeper = gatekeeper();
        mode.set(true);
        assertThat(autorizar(gatekeeper, false)).isInstanceOf(Gatekeeper.Permit.Granted.class);

        mode.set(false);

        assertThat(autorizar(gatekeeper, false)).isInstanceOf(Gatekeeper.Permit.Refused.class);
    }

    /** Uma auditoria que só guarda o que recebeu, para ver o {@code decidedBy}. */
    private static final class Captura implements AuditLog {

        private final List<Entry> entries = new ArrayList<>();

        @Override public long begin(Entry entry) {
            entries.add(entry);
            return entries.size();
        }

        @Override public void complete(String callId, Status status, Duration took, String summary, String error) {}

        @Override public Verification verify(int lastN) {
            return new Verification(true, entries.size(), -1);
        }

        @Override public long entries() {
            return entries.size();
        }

        @Override public void close() {}
    }
}
