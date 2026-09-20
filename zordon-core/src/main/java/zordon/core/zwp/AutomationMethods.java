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
package zordon.core.zwp;

import java.util.Map;
import zordon.api.trace.Spec;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.automation.AutomationEngine;

/** Aprovação e disparos manuais exigem uma sessão de desktop. */
@Spec("SPEC-025")
public final class AutomationMethods {
    private final AutomationEngine engine;
    public AutomationMethods(AutomationEngine engine) { this.engine = engine; }

    public void registerOn(ZwpServer server) {
        server.register("automation.list", (session, params) -> engine.list());
        register(server, "automation.propose", false, params -> {
            if (!(params.get("spec") instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("spec é obrigatório");
            }
            @SuppressWarnings("unchecked") Map<String, Object> spec = (Map<String, Object>) raw;
            return engine.propose(spec);
        });
        register(server, "automation.approve", true, params -> Map.of("id", engine.approve(required(params, "proposalId"))));
        register(server, "automation.reject", true, params -> Map.of("rejected", engine.reject(required(params, "proposalId"))));
        register(server, "automation.enable", true, params -> Map.of("enabled", engine.enable(required(params, "id"), true)));
        register(server, "automation.disable", true, params -> Map.of("enabled", engine.enable(required(params, "id"), false)));
        register(server, "automation.run", true, params -> Map.of("taskId", engine.fire(required(params, "id"), Map.of())
                .orElseThrow(() -> new IllegalArgumentException("disparo pulado: desativada, lockdown ou execução em curso"))));
    }

    private void register(ZwpServer server, String method, boolean desktop,
            java.util.function.Function<Map<String, Object>, Map<String, Object>> handler) {
        server.register(method, (session, params) -> {
            if (desktop && !session.is(ClientKind.DESKTOP)) {
                throw new ZwpMethodException(ZwpErrorKind.ERR_PERMISSION_DENIED, "só a janela do Zordon decide isto");
            }
            try {
                return handler.apply(params);
            } catch (IllegalArgumentException e) {
                throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, e.getMessage());
            }
        });
    }

    private static String required(Map<String, Object> params, String name) {
        if (!(params.get(name) instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " é obrigatório");
        }
        return text;
    }
}
