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
        if (action.effects().contains(Effect.MODIFY_TRUST_KERNEL)) {
            return new Decision.Deny(RiskLevel.RED, "núcleo de confiança: só proposto por PR, nunca aplicado");
        }
        if (ctx.breakerOpen()) {
            return new Decision.Deny(action.baseRisk(), "disjuntor aberto para " + actor.actor());
        }
        List<String> why = new ArrayList<>();
        RiskLevel risk = action.baseRisk();
        // O risco da ação em si, sem o que a origem acrescenta: é contra ele que vale o teto do
        // agente. Um sub-agente de teto GREEN ainda lê; o nível extra da delegação só decide se
        // o usuário precisa confirmar (SPEC-022).
        RiskLevel intrinsic = action.baseRisk();
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
                risk = RiskLevel.RED;
                intrinsic = RiskLevel.RED;
                why.add("caminho crítico " + path);
            } else if (writes ? !verdict.workspace() : !verdict.readable()) {
                risk = risk.raise();
                intrinsic = intrinsic.raise();
                why.add("fora das áreas permitidas: " + path);
            }
        }
        if (action.targets() > TARGET_LIMIT) {
            risk = risk.raise();
            intrinsic = intrinsic.raise();
            why.add(action.targets() + " alvos");
        }
        if (redactor.containsSecret(action.args())) {
            risk = RiskLevel.RED;
            intrinsic = RiskLevel.RED;
            why.add("argumento contém segredo");
        }
        if (ctx.newTool()) {
            risk = risk.raise();
            intrinsic = intrinsic.raise();
            why.add("ferramenta nova");
        }
        if (actor.origin() == RequestOrigin.AUTOMATION && !ctx.userPresent()) {
            risk = risk.raise();
            why.add("automação sem usuário presente");
        }
        if (actor.delegated()) {
            risk = risk.raise();
            why.add("pedido por agente delegado");
        }
        if (action.effects().contains(Effect.MODIFY_SELF) || action.effects().contains(Effect.MODIFY_PROJECT)) {
            risk = risk.atLeast(RiskLevel.YELLOW);
            intrinsic = intrinsic.atLeast(RiskLevel.YELLOW);
        }
        if (ctx.tainted() && (action.effects().contains(Effect.EXPORT_DATA)
                || action.effects().contains(Effect.NETWORK))) {
            risk = RiskLevel.RED;
            intrinsic = RiskLevel.RED;
            why.add("turno contaminado enviando dados");
        }
        if (!action.command().isEmpty()) {
            switch (commands.validate(action.command())) {
                case CommandValidator.Validation.Denied denied -> {
                    return new Decision.Deny(RiskLevel.RED, denied.reason());
                }
                case CommandValidator.Validation.Accepted accepted -> {
                    if (accepted.floor().compareTo(risk) > 0) {
                        risk = accepted.floor();
                        why.add(accepted.note());
                    }
                    if (accepted.floor().compareTo(intrinsic) > 0) {
                        intrinsic = accepted.floor();
                    }
                }
            }
        }
        if (ctx.ceiling() != null && intrinsic.compareTo(ctx.ceiling()) > 0) {
            // Teto do agente é trava dura (Interfaces §7): nem chega a perguntar ao usuário.
            return new Decision.Deny(intrinsic, "acima do teto do agente (" + ctx.ceiling().wire() + ")");
        }
        if (ctx.lockdown() && risk != RiskLevel.GREEN) {
            return new Decision.Deny(risk, "Zordon em lockdown: só leitura");
        }
        return decide(action, actor, ctx, risk, why.isEmpty() ? action.humanSummary()
                : action.humanSummary() + " (" + String.join("; ", why) + ")");
    }

    /** O teto de cada origem (identity.md §3). Agente herda a origem de quem o iniciou. */
    private Decision decide(ActionDescriptor action, Principal actor, PolicyContext ctx, RiskLevel risk, String why) {
        RequestOrigin origin = actor.origin() == RequestOrigin.AGENT ? RequestOrigin.AUTOMATION : actor.origin();
        return switch (risk) {
            case GREEN -> origin == RequestOrigin.AUTONOMOUS && action.effects().stream().anyMatch(WRITES::contains)
                    ? new Decision.Deny(risk, "autônomo só faz contenção reversível")
                    : new Decision.Allow(risk, why, false);
            case YELLOW -> switch (origin) {
                case UI -> sessionGrants.contains(grantKey(action))
                        ? new Decision.Allow(risk, why + " — permitido nesta sessão", true)
                        : new Decision.AskUser(risk, why, APPROVAL_TTL, false);
                case VOICE -> new Decision.AskUser(risk, why + " — confirme na tela", APPROVAL_TTL, true);
                case AUTOMATION -> ctx.automationScope().contains(action.tool())
                        ? new Decision.Allow(risk, why + " — no escopo aprovado da automação", false)
                        : new Decision.Deny(risk, "fora do escopo aprovado da automação");
                case AUTONOMOUS, AGENT -> new Decision.Deny(risk, "origem sem autoridade para efeito");
            };
            case RED -> switch (origin) {
                case UI, VOICE -> new Decision.AskUser(risk, why, APPROVAL_TTL, true);
                case AUTOMATION, AUTONOMOUS, AGENT ->
                        new Decision.Deny(risk, "RED nunca roda sem alguém autorizar na tela");
            };
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
                .handle((approval, failure) -> {
                    Throwable cause = failure instanceof java.util.concurrent.CompletionException wrapped
                            && wrapped.getCause() != null ? wrapped.getCause() : failure;
                    if (cause instanceof java.util.concurrent.TimeoutException || (failure == null && approval == null)) {
                        return new Decision.Deny(ask.risk(), "sem resposta em " + ask.ttl().toSeconds() + " s");
                    }
                    if (failure != null) {
                        return new Decision.Deny(ask.risk(), "sem tela para autorizar: " + cause.getMessage());
                    }
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
                });
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
