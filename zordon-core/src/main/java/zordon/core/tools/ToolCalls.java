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

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Principal;
import zordon.api.security.RiskLevel;
import zordon.core.event.ZordonEventBus;
import zordon.security.Gatekeeper;
import zordon.security.PermissionEngine;

/** O caminho de uma chamada até o {@link Gatekeeper}, com o contexto de política certo para cada origem. */
final class ToolCalls {

    private final Gatekeeper gatekeeper;
    private final Map<String, Tool> tools;
    private final Set<String> tainted;
    private final BooleanSupplier lockdown;
    private final ToolWiring wiring;
    private volatile Predicate<Principal> breakerOpen = actor -> false;

    ToolCalls(Gatekeeper gatekeeper, ZordonEventBus bus, BooleanSupplier lockdown, Map<String, Tool> tools,
            Set<String> tainted) {
        this.gatekeeper = gatekeeper;
        this.tools = tools;
        this.tainted = tainted;
        this.lockdown = lockdown;
        this.wiring = ToolWiring.of(bus, tainted);
    }

    ToolWiring wiring() {
        return wiring;
    }

    void breakerBy(Predicate<Principal> open) {
        this.breakerOpen = Objects.requireNonNull(open, "open");
    }

    /** Com o teto do agente: acima dele, o motor nega sem perguntar. */
    CompletableFuture<SkillRuntime.Outcome> forUser(String name, Map<String, Object> args, Principal actor,
            String turnId, RiskLevel ceiling, Consumer<Decision> decided) {
        return call(name, args, actor, turnId, new PermissionEngine.PolicyContext(lockdown.getAsBoolean(),
                breakerOpen.test(actor), tainted(turnId), true, false, Set.of(), ceiling), decided);
    }

    /** Sem usuário presente (a ação sobe um nível) e com o escopo aprovado. RED nunca roda; o motor garante. */
    CompletableFuture<SkillRuntime.Outcome> forAutomation(String name, Map<String, Object> args, Principal actor,
            String runId, Set<String> approvedScope) {
        return call(name, args, actor, runId, new PermissionEngine.PolicyContext(lockdown.getAsBoolean(), false,
                tainted(runId), false, false, approvedScope, RiskLevel.GREEN), decision -> { });
    }

    private boolean tainted(String turnId) {
        return turnId != null && tainted.contains(turnId);
    }

    private CompletableFuture<SkillRuntime.Outcome> call(String name, Map<String, Object> args, Principal actor,
            String turnId, PermissionEngine.PolicyContext ctx, Consumer<Decision> decided) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return CompletableFuture.completedFuture(new SkillRuntime.Outcome("failed",
                    ToolResult.of("Não conheço a ferramenta " + name + ".")));
        }
        ActionDescriptor action;
        try {
            action = tool.describe(args);
        } catch (ToolException e) {
            return CompletableFuture.completedFuture(new SkillRuntime.Outcome("failed",
                    ToolResult.of(SkillRuntime.capitalized(e.getMessage()) + ".")));
        }
        ToolCall call = new ToolCall(tool, action, actor, turnId, ctx.tainted());
        return gatekeeper.authorize(action, actor, ctx, turnId).thenApplyAsync(
                permit -> call.decided(permit, args, decided, wiring),
                runnable -> Thread.ofVirtual().name("zordon-tool-" + name).start(runnable));
    }
}
