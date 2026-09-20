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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;
import zordon.api.security.ZPath;
import zordon.api.trace.AcceptanceCriteria;

/** O motor de permissão contra a tabela golden e o fluxo de aprovação (SPEC-014). */
class PermissionEngineTest {

    private static final String HOME = "/home/u";

    private static DefaultPermissionEngine engine(PermissionEngine.Approver approver) {
        return new DefaultPermissionEngine(
                PathPolicy.defaults(HOME, List.of("~/dev", "D:/projetos"), List.of("~", "D:/")),
                new CommandValidator(Map.of("git", "/usr/bin/git", "docker", "/usr/bin/docker",
                        "gradle", "/opt/gradle/bin/gradle")),
                new Redactor(), () -> approver);
    }

    record Case(String id, ActionDescriptor action, Principal principal, PermissionEngine.PolicyContext ctx,
            RiskLevel risk, String decision) {}

    static List<Case> golden() throws IOException {
        List<Case> cases = new ArrayList<>();
        try (InputStream in = PermissionEngineTest.class.getResourceAsStream("/golden/permissions.tsv")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                Set<Effect> effects = new HashSet<>();
                if (!f[3].equals("-")) {
                    Arrays.stream(f[3].split(",")).map(Effect::valueOf).forEach(effects::add);
                }
                List<ZPath> paths = new ArrayList<>();
                if (!f[4].equals("-")) {
                    for (String p : f[4].split(",")) {
                        paths.add(p.startsWith("W:") ? ZPath.ofWindows(p.substring(2))
                                : ZPath.ofWsl(p.startsWith("~") ? HOME + p.substring(1) : p));
                    }
                }
                List<String> command = f[6].equals("-") ? List.of() : List.of(f[6].split(" "));
                Map<String, Object> args = new LinkedHashMap<>();
                if (!f[7].equals("-")) {
                    for (String pair : f[7].split(";")) {
                        int eq = pair.indexOf('=');
                        args.put(pair.substring(0, eq), pair.substring(eq + 1));
                    }
                }
                Set<String> flags = f[10].equals("-") ? Set.of() : Set.of(f[10].split(","));
                Set<String> scope = new HashSet<>();
                flags.stream().filter(flag -> flag.startsWith("scope=")).forEach(flag -> scope.add(flag.substring(6)));
                PermissionEngine.PolicyContext ctx = new PermissionEngine.PolicyContext(flags.contains("lockdown"),
                        flags.contains("breaker"), flags.contains("tainted"), !flags.contains("absent"),
                        flags.contains("newtool"), scope);
                ActionDescriptor action = new ActionDescriptor(f[1], args, RiskLevel.valueOf(f[2].toUpperCase()),
                        effects, paths, Integer.parseInt(f[5]), command, "caso " + f[0]);
                Principal principal = new Principal(f[9].equals("1") ? "agent:executor" : "user",
                        RequestOrigin.valueOf(f[8].toUpperCase()), f[9].equals("1"));
                cases.add(new Case(f[0], action, principal, ctx, RiskLevel.valueOf(f[11].toUpperCase()), f[12]));
            }
        }
        return cases;
    }

    private static String wire(Decision decision) {
        return decision instanceof Decision.AskUser ask && ask.perAction() ? "ask-action" : decision.wire();
    }

    @AcceptanceCriteria("SPEC-014/CA-6")
    @Test
    void cadaCasoDaTabelaGoldenDaExatamenteORiscoEADecisaoRegistrados() throws IOException {
        DefaultPermissionEngine engine = engine(null);
        List<Case> cases = golden();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(40);
        List<String> mismatches = new ArrayList<>();
        for (Case c : cases) {
            Decision got = engine.evaluate(c.action(), c.principal(), c.ctx());
            if (got.risk() != c.risk() || !wire(got).equals(c.decision())) {
                mismatches.add(c.id() + ": esperado " + c.risk().wire() + "/" + c.decision() + ", veio "
                        + got.risk().wire() + "/" + wire(got) + " (" + got.reason() + ")");
            }
        }
        assertThat(mismatches).as("tabela golden").isEmpty();
    }

    private static ActionDescriptor write(String path) {
        return new ActionDescriptor("fs.write", Map.of(), RiskLevel.YELLOW, Set.of(Effect.WRITE_FS),
                List.of(ZPath.ofWsl(path)), 1, List.of(), "Escrever " + path);
    }

    private static ActionDescriptor quarantine() {
        return new ActionDescriptor("fs.quarantine", Map.of(), RiskLevel.RED, Set.of(Effect.QUARANTINE_FS),
                List.of(ZPath.ofWsl(HOME + "/dev/app/logs")), 43, List.of(),
                "Mover 43 arquivos de ~/dev/app/logs para a quarentena");
    }

    @AcceptanceCriteria("SPEC-014/CA-7")
    @Test
    void redPerguntaPorAcaoESemAprovadorOuSemRespostaNega() throws Exception {
        Principal user = Principal.user(RequestOrigin.UI);
        PermissionEngine.PolicyContext ctx = PermissionEngine.PolicyContext.interactive();

        Decision.AskUser ask = (Decision.AskUser) engine(null).evaluate(quarantine(), user, ctx);
        assertThat(ask.perAction()).isTrue();
        assertThat(ask.ttl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(engine(null).requestApproval(quarantine(), user, ask).get(1, TimeUnit.SECONDS))
                .isInstanceOf(Decision.Deny.class).extracting(Decision::reason).asString().contains("nenhuma tela");

        PermissionEngine.Approver silent = (action, actor, risk, ttl, perAction) -> new CompletableFuture<>();
        Decision.AskUser quick = new Decision.AskUser(RiskLevel.RED, "x", Duration.ofMillis(50), true);
        assertThat(engine(silent).requestApproval(quarantine(), Principal.user(RequestOrigin.UI), quick).get(2, TimeUnit.SECONDS))
                .isInstanceOf(Decision.Deny.class).extracting(Decision::reason).asString().contains("sem resposta");

        // "Nesta sessão" não vale para RED: autoriza só esta, e a próxima pergunta de novo.
        DefaultPermissionEngine sessionClick = engine(
                (action, actor, risk, ttl, perAction) -> CompletableFuture.completedFuture(PermissionEngine.Approval.SESSION));
        assertThat(sessionClick.requestApproval(quarantine(), user, ask).get(1, TimeUnit.SECONDS))
                .isEqualTo(new Decision.Allow(RiskLevel.RED, "autorizado pelo usuário, só desta vez", false));
        assertThat(sessionClick.evaluate(quarantine(), user, ctx)).isInstanceOf(Decision.AskUser.class);
    }

    @AcceptanceCriteria("SPEC-022/CA-2")
    @Test
    void tetoDoAgenteNegaSemPerguntarEADelegacaoSoPedeConfirmacao() {
        List<String> asked = new ArrayList<>();
        DefaultPermissionEngine engine = engine((action, actor, risk, ttl, perAction) -> {
            asked.add(action.tool());
            return CompletableFuture.completedFuture(PermissionEngine.Approval.ONCE);
        });
        PermissionEngine.PolicyContext green = new PermissionEngine.PolicyContext(false, false, false, true, false,
                Set.of(), RiskLevel.GREEN);
        Principal user = Principal.user(RequestOrigin.UI);

        Decision write = engine.evaluate(write(HOME + "/dev/app/a.txt"), user, green);
        assertThat(write).isInstanceOf(Decision.Deny.class);
        assertThat(write.reason()).startsWith("acima do teto do agente (green)");

        ActionDescriptor read = new ActionDescriptor("fs.read", Map.of(), RiskLevel.GREEN, Set.of(Effect.READ_FS),
                List.of(ZPath.ofWsl(HOME + "/dev/app/README.md")), 1, List.of(), "Ler o README");
        assertThat(engine.evaluate(read, user, green)).isInstanceOf(Decision.Allow.class);
        Decision delegated = engine.evaluate(read, new Principal("agent:research", RequestOrigin.UI, true), green);
        assertThat(delegated).as("o nível da delegação não conta contra o teto: pergunta")
                .isInstanceOf(Decision.AskUser.class);
        assertThat(delegated.risk()).isEqualTo(RiskLevel.YELLOW);
        assertThat(engine.evaluate(write(HOME + "/dev/app/a.txt"), user, PermissionEngine.PolicyContext.interactive()))
                .as("sem agente, sem teto").isInstanceOf(Decision.AskUser.class);
        assertThat(asked).isEmpty();
    }

    @Test
    void yellowNaSessaoValeParaAMesmaFerramentaEArea() throws Exception {
        DefaultPermissionEngine engine = engine(
                (action, actor, risk, ttl, perAction) -> CompletableFuture.completedFuture(PermissionEngine.Approval.SESSION));
        Principal user = Principal.user(RequestOrigin.UI);
        PermissionEngine.PolicyContext ctx = PermissionEngine.PolicyContext.interactive();
        ActionDescriptor first = write(HOME + "/dev/app/a.txt");

        Decision.AskUser ask = (Decision.AskUser) engine.evaluate(first, user, ctx);
        assertThat(engine.requestApproval(first, user, ask).get(1, TimeUnit.SECONDS)).isInstanceOf(Decision.Allow.class);

        assertThat(engine.evaluate(write(HOME + "/dev/app/b.txt"), user, ctx))
                .isInstanceOf(Decision.Allow.class).extracting(d -> ((Decision.Allow) d).session()).isEqualTo(true);
        assertThat(engine.evaluate(write(HOME + "/dev/outro/c.txt"), user, ctx)).isInstanceOf(Decision.AskUser.class);
        assertThat(engine.evaluate(write(HOME + "/dev/app/b.txt"), Principal.user(RequestOrigin.VOICE), ctx))
                .as("por voz, sempre na tela").isInstanceOf(Decision.AskUser.class);
    }

    @AcceptanceCriteria("SPEC-014/CA-8")
    @Test
    void lockdownDisjuntorETetoDaOrigem() {
        DefaultPermissionEngine engine = engine(null);
        Principal user = Principal.user(RequestOrigin.UI);
        PermissionEngine.PolicyContext lockdown = new PermissionEngine.PolicyContext(true, false, false, true, false,
                Set.of());
        assertThat(engine.evaluate(write(HOME + "/dev/a"), user, lockdown)).isInstanceOf(Decision.Deny.class);
        PermissionEngine.PolicyContext breaker = new PermissionEngine.PolicyContext(false, true, false, true, false,
                Set.of());
        assertThat(engine.evaluate(write(HOME + "/dev/a"), user, breaker)).isInstanceOf(Decision.Deny.class);

        PermissionEngine.PolicyContext scoped = new PermissionEngine.PolicyContext(false, false, false, true, false,
                Set.of("fs.quarantine"));
        assertThat(engine.evaluate(quarantine(), new Principal("automation:limpeza", RequestOrigin.AUTOMATION, false),
                scoped)).isInstanceOf(Decision.Deny.class);
        assertThat(engine.evaluate(quarantine(), new Principal("defense", RequestOrigin.AUTONOMOUS, false),
                PermissionEngine.PolicyContext.interactive())).isInstanceOf(Decision.Deny.class);
    }
}
