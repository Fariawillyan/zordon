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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Effect;
import zordon.api.security.Principal;
import zordon.api.security.RequestOrigin;
import zordon.api.security.RiskLevel;
import zordon.api.security.ZPath;
import zordon.api.trace.Spec;

/**
 * Risco = piso da ferramenta + escalonamento por argumento
 * (docs/security/model.md §2); decisão = risco × teto da origem
 * (docs/security/identity.md §3). A tabela golden fixa o resultado.
 */
@Spec("SPEC-014")
public final class DefaultPermissionEngine implements PermissionEngine {

    /** Glob que casa mais alvos que isto sobe um nível. */
    public static final int TARGET_LIMIT = 20;

    private static final Set<Effect> WRITES = Set.of(Effect.WRITE_FS, Effect.QUARANTINE_FS, Effect.MODIFY_SELF,
            Effect.MODIFY_PROJECT, Effect.MODIFY_SYSTEM, Effect.MODIFY_CREDENTIALS, Effect.MODIFY_TRUST_KERNEL);

    private final PathPolicy paths;
    private final CommandValidator commands;
    private final Redactor redactor;
    private final Supplier<Approver> approver;
    /** Permissões "nesta sessão" de YELLOW: (ferramenta, área). Vivem até o núcleo reiniciar. */
    private final Set<String> sessionGrants = ConcurrentHashMap.newKeySet();

    public DefaultPermissionEngine(PathPolicy paths, CommandValidator commands, Redactor redactor,
            Supplier<Approver> approver) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.approver = Objects.requireNonNull(approver, "approver");
    }

    @Override
    public Decision evaluate(ActionDescriptor action, Principal actor, PolicyContext ctx) {
        Objects.requireNonNull(actor, "principal");
        Decision refused = refuseOutright(action, actor, ctx);
        if (refused != null) {
            return refused;
        }
        Risks risks = new Risks(action.baseRisk());
        Decision forbidden = classifyPaths(action, risks);
        if (forbidden != null) {
            return forbidden;
        }
        escalateByAction(action, ctx, risks);
        escalateByOrigin(actor, ctx, risks);
        Decision denied = applyCommand(action, risks);
        if (denied != null) {
            return denied;
        }
        if (ctx.ceiling() != null && risks.intrinsic.compareTo(ctx.ceiling()) > 0) {
            // Teto do agente é trava dura (Interfaces §7): nem chega a perguntar ao usuário.
            return new Decision.Deny(risks.intrinsic, "acima do teto do agente (" + ctx.ceiling().wire() + ")");
        }
        if (ctx.lockdown() && risks.risk != RiskLevel.GREEN) {
            return new Decision.Deny(risks.risk, "Zordon em lockdown: só leitura");
        }
        return decide(new Request(action, actor, ctx), risks.risk, risks.summary(action));
    }

    /**
     * O risco em construção durante uma avaliação.
     *
     * <p>São dois números, e a diferença importa: {@code risk} é o do pedido e
     * decide se o usuário confirma; {@code intrinsic} é o da ação em si, sem o
     * que a origem acrescenta, e é contra ele que vale o teto do agente. Um
     * sub-agente de teto GREEN ainda lê; o nível extra da delegação só muda a
     * confirmação (SPEC-022).
     */
    private static final class Risks {

        private RiskLevel risk;
        private RiskLevel intrinsic;
        private final List<String> why = new ArrayList<>();

        Risks(RiskLevel base) {
            this.risk = base;
            this.intrinsic = base;
        }

        /** A ação em si ficou mais arriscada: sobe os dois. */
        void raise(String reason) {
            risk = risk.raise();
            intrinsic = intrinsic.raise();
            why.add(reason);
        }

        /** Só quem pediu mudou: sobe o do pedido, não o da ação. */
        void raiseRequest(String reason) {
            risk = risk.raise();
            why.add(reason);
        }

        void red(String reason) {
            risk = RiskLevel.RED;
            intrinsic = RiskLevel.RED;
            why.add(reason);
        }

        void atLeast(RiskLevel level) {
            risk = risk.atLeast(level);
            intrinsic = intrinsic.atLeast(level);
        }

        /** Piso vindo do validador de comando: só vira motivo se de fato subiu o pedido. */
        void floor(RiskLevel level, String note) {
            if (level.compareTo(risk) > 0) {
                risk = level;
                why.add(note);
            }
            if (level.compareTo(intrinsic) > 0) {
                intrinsic = level;
            }
        }

        String summary(ActionDescriptor action) {
            return why.isEmpty() ? action.humanSummary() : action.humanSummary() + " (" + String.join("; ", why) + ")";
        }
    }

    /** Quem pede o quê, em que contexto: junto porque as três andam sempre juntas. */
    private record Request(ActionDescriptor action, Principal actor, PolicyContext ctx) {}

    /** As recusas que não dependem de nada mais. {@code null} quando não há. */
    private static Decision refuseOutright(ActionDescriptor action, Principal actor, PolicyContext ctx) {
        if (action.effects().contains(Effect.MODIFY_TRUST_KERNEL)) {
            return new Decision.Deny(RiskLevel.RED, "núcleo de confiança: só proposto por PR, nunca aplicado");
        }
        if (ctx.breakerOpen()) {
            return new Decision.Deny(action.baseRisk(), "disjuntor aberto para " + actor.actor());
        }
        return null;
    }

    /** Classifica cada caminho tocado. Devolve a recusa, ou {@code null} e sobe o risco. */
    private Decision classifyPaths(ActionDescriptor action, Risks risks) {
        boolean writes = action.effects().stream().anyMatch(WRITES::contains);
        for (ZPath path : action.touchedPaths()) {
            PathPolicy.Verdict verdict = paths.classify(path);
            if (verdict.forbidden()) {
                return new Decision.Deny(RiskLevel.RED, "caminho proibido pela política: " + path);
            }
            if (verdict.install() && writes) {
                return new Decision.Deny(RiskLevel.RED, "escrita no caminho de instalação do Zordon: " + path);
            }
            if (verdict.critical()) {
                risks.red("caminho crítico " + path);
            } else if (writes ? !verdict.workspace() : !verdict.readable()) {
                risks.raise("fora das áreas permitidas: " + path);
            }
        }
        return null;
    }

    /** O que a ação em si acrescenta ao risco. */
    private void escalateByAction(ActionDescriptor action, PolicyContext ctx, Risks risks) {
        if (action.targets() > TARGET_LIMIT) {
            risks.raise(action.targets() + " alvos");
        }
        if (redactor.containsSecret(action.args())) {
            risks.red("argumento contém segredo");
        }
        if (ctx.newTool()) {
            risks.raise("ferramenta nova");
        }
        if (action.effects().contains(Effect.MODIFY_SELF) || action.effects().contains(Effect.MODIFY_PROJECT)) {
            risks.atLeast(RiskLevel.YELLOW);
        }
        if (ctx.tainted() && (action.effects().contains(Effect.EXPORT_DATA)
                || action.effects().contains(Effect.NETWORK))) {
            risks.red("turno contaminado enviando dados");
        }
    }

    /** O que a origem do pedido acrescenta — sem tocar no risco intrínseco. */
    private static void escalateByOrigin(Principal actor, PolicyContext ctx, Risks risks) {
        if (actor.origin() == RequestOrigin.AUTOMATION && !ctx.userPresent()) {
            risks.raiseRequest("automação sem usuário presente");
        }
        if (actor.delegated()) {
            risks.raiseRequest("pedido por agente delegado");
        }
    }

    /** O piso que o validador de comando impõe. Devolve a recusa, ou {@code null}. */
    private Decision applyCommand(ActionDescriptor action, Risks risks) {
        if (action.command().isEmpty()) {
            return null;
        }
        return switch (commands.validate(action.command())) {
            case CommandValidator.Validation.Denied denied -> new Decision.Deny(RiskLevel.RED, denied.reason());
            case CommandValidator.Validation.Accepted accepted -> {
                risks.floor(accepted.floor(), accepted.note());
                yield null;
            }
        };
    }

    /** O teto de cada origem (identity.md §3). Agente herda a origem de quem o iniciou. */
    private Decision decide(Request request, RiskLevel risk, String why) {
        RequestOrigin origin = request.actor().origin() == RequestOrigin.AGENT
                ? RequestOrigin.AUTOMATION : request.actor().origin();
        return switch (risk) {
            case GREEN -> green(request.action(), origin, risk, why);
            case YELLOW -> yellow(request, origin, risk, why);
            case RED -> red(origin, risk, why);
        };
    }

    /** GREEN passa direto, menos para o autônomo, que só contém — e conter não escreve. */
    private static Decision green(ActionDescriptor action, RequestOrigin origin, RiskLevel risk, String why) {
        return origin == RequestOrigin.AUTONOMOUS && action.effects().stream().anyMatch(WRITES::contains)
                ? new Decision.Deny(risk, "autônomo só faz contenção reversível")
                : new Decision.Allow(risk, why, false);
    }

    private Decision yellow(Request request, RequestOrigin origin, RiskLevel risk, String why) {
        return switch (origin) {
            case UI -> sessionGrants.contains(grantKey(request.action()))
                    ? new Decision.Allow(risk, why + " — permitido nesta sessão", true)
                    : new Decision.AskUser(risk, why, APPROVAL_TTL, false);
            case VOICE -> new Decision.AskUser(risk, why + " — confirme na tela", APPROVAL_TTL, true);
            case AUTOMATION -> request.ctx().automationScope().contains(request.action().tool())
                    ? new Decision.Allow(risk, why + " — no escopo aprovado da automação", false)
                    : new Decision.Deny(risk, "fora do escopo aprovado da automação");
            case AUTONOMOUS, AGENT -> new Decision.Deny(risk, "origem sem autoridade para efeito");
        };
    }

    private static Decision red(RequestOrigin origin, RiskLevel risk, String why) {
        return switch (origin) {
            case UI, VOICE -> new Decision.AskUser(risk, why, APPROVAL_TTL, true);
            case AUTOMATION, AUTONOMOUS, AGENT ->
                    new Decision.Deny(risk, "RED nunca roda sem alguém autorizar na tela");
        };
    }

    @Override
    public CompletableFuture<Decision> requestApproval(ActionDescriptor action, Principal actor, Decision.AskUser ask) {
        Approver current = approver.get();
        if (current == null) {
            return CompletableFuture.completedFuture(new Decision.Deny(ask.risk(), "nenhuma tela para autorizar"));
        }
        return current.ask(action, actor, ask.risk(), ask.ttl(), ask.perAction())
                .orTimeout(ask.ttl().toMillis(), TimeUnit.MILLISECONDS)
                .handle((approval, failure) -> settle(action, ask, approval, failure));
    }

    /** O que a resposta da tela — ou a falta dela — significa. Sem resposta é negação. */
    private Decision settle(ActionDescriptor action, Decision.AskUser ask, Approval approval, Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException wrapped
                && wrapped.getCause() != null ? wrapped.getCause() : failure;
        if (cause instanceof java.util.concurrent.TimeoutException || (failure == null && approval == null)) {
            return new Decision.Deny(ask.risk(), "sem resposta em " + ask.ttl().toSeconds() + " s");
        }
        if (failure != null) {
            return new Decision.Deny(ask.risk(), "sem tela para autorizar: " + cause.getMessage());
        }
        return granted(action, ask, approval);
    }

    private Decision granted(ActionDescriptor action, Decision.AskUser ask, Approval approval) {
        return switch (approval) {
            case DENY -> new Decision.Deny(ask.risk(), "negado pelo usuário");
            case ONCE -> new Decision.Allow(ask.risk(), "autorizado pelo usuário", false);
            case SESSION -> {
                if (ask.perAction()) {
                    yield new Decision.Allow(ask.risk(), "autorizado pelo usuário, só desta vez", false);
                }
                sessionGrants.add(grantKey(action));
                yield new Decision.Allow(ask.risk(), "autorizado nesta sessão", true);
            }
        };
    }

    /** Área de uma permissão de sessão: a ferramenta e a pasta do primeiro caminho. */
    private static String grantKey(ActionDescriptor action) {
        String area = action.touchedPaths().isEmpty() ? "" : parent(action.touchedPaths().getFirst().comparable());
        return action.tool() + "|" + area;
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? path : path.substring(0, slash);
    }
}
