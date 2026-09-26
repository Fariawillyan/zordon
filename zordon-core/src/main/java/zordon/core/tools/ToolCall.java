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
import java.util.function.Consumer;
import zordon.api.event.EventType;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Decision;
import zordon.api.security.Principal;
import zordon.core.event.ZordonEventBus;
import zordon.security.Gatekeeper;

/** Uma chamada depois da decisão do Gatekeeper: publicada, observada e, se liberada, executada. */
final class ToolCall {

    private final Tool tool;
    private final ActionDescriptor action;
    private final Principal actor;
    private final String turnId;
    private final boolean tainted;

    ToolCall(Tool tool, ActionDescriptor action, Principal actor, String turnId, boolean tainted) {
        this.tool = tool;
        this.action = action;
        this.actor = actor;
        this.turnId = turnId;
        this.tainted = tainted;
    }

    SkillRuntime.Outcome decided(Gatekeeper.Permit permit, Map<String, Object> args, Consumer<Decision> decided,
            ToolWiring wiring) {
        ZordonEventBus bus = wiring.bus();
        wiring.observers().decided(decided, permit.decision());
        bus.publish(EventType.TOOL_CALLED, Map.of("callId", permit.callId(), "tool", tool.name(),
                "risk", permit.decision().risk().wire(), "decision", permit.decision().wire()));
        wiring.observers().called(action, actor, turnId, permit.decision(), tool.effects(), tainted);
        return switch (permit) {
            case Gatekeeper.Permit.Refused refused -> {
                bus.publish(EventType.TOOL_RESULT, Map.of("callId", permit.callId(), "tool", tool.name(),
                        "status", "refused", "durationMs", 0));
                yield new SkillRuntime.Outcome("refused",
                        ToolResult.of("Não fiz: " + refused.decision().reason() + "."));
            }
            case Gatekeeper.Permit.Granted granted -> wiring.execution().run(tool, granted, args, turnId);
        };
    }
}
