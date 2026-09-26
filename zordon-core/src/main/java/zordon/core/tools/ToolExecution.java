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

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zordon.api.event.EventType;
import zordon.api.security.Effect;
import zordon.core.event.ZordonEventBus;
import zordon.security.AuditLog;
import zordon.security.Gatekeeper;

/** Uma ferramenta já autorizada: roda, grava o desfecho na auditoria e avisa quem acompanha. */
final class ToolExecution {

    private static final Logger log = LoggerFactory.getLogger(SkillRuntime.class);

    private final ZordonEventBus bus;
    private final ToolObservers observers;
    private final Set<String> tainted;

    /** @param tainted os turnos que já leram conteúdo; uma leitura OK marca o turno */
    ToolExecution(ZordonEventBus bus, ToolObservers observers, Set<String> tainted) {
        this.bus = bus;
        this.observers = observers;
        this.tainted = tainted;
    }

    SkillRuntime.Outcome run(Tool tool, Gatekeeper.Permit.Granted permit, Map<String, Object> args, String turnId) {
        long started = System.nanoTime();
        ToolResult result;
        AuditLog.Status status;
        String error = null;
        try {
            result = tool.run(permit, args, turnId);
            status = AuditLog.Status.OK;
        } catch (ToolException e) {
            result = ToolResult.of(SkillRuntime.capitalized(e.getMessage()) + ".");
            status = AuditLog.Status.FAILED;
            error = e.getMessage();
        } catch (Exception e) {
            log.warn("{} falhou: {}", tool.name(), e.toString());
            result = ToolResult.of("Não consegui: " + e.getMessage() + ".");
            status = AuditLog.Status.FAILED;
            error = e.toString();
        }
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        if (status == AuditLog.Status.OK && turnId != null && tool.effects().contains(Effect.READ_FS)) {
            tainted.add(turnId);
        }
        permit.complete(new AuditLog.Completion(status, took, result.text(), error));
        if (status == AuditLog.Status.OK) {
            observers.completed(permit.action(), turnId);
        }
        bus.publish(EventType.TOOL_RESULT, Map.of("callId", permit.callId(), "tool", tool.name(),
                "status", status.wire(), "durationMs", took.toMillis()));
        observers.result(permit.action(), turnId, result.text(), status.wire());
        return new SkillRuntime.Outcome(status == AuditLog.Status.OK ? "ok" : "failed", result);
    }
}
