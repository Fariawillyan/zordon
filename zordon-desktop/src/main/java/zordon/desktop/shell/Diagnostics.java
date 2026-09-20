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
package zordon.desktop.shell;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** O que {@code system.diagnostics} devolveu, já tipado para a tela (SPEC-005 §7). */
public record Diagnostics(
        String version,
        String startId,
        long uptimeSeconds,
        long lastSeq,
        int clients,
        int activeTurns,
        Map<String, String> providers,
        Map<String, RoleState> roles) {

    /** Quem atende um papel, e se está pronto. */
    public record RoleState(String provider, String model, boolean ready, String reason) {}

    public static Diagnostics from(Map<String, Object> result) {
        Map<?, ?> core = map(result.get("core"));
        Map<String, String> providers = new LinkedHashMap<>();
        map(result.get("providers")).forEach((id, state) -> providers.put(String.valueOf(id), String.valueOf(state)));
        Map<String, RoleState> roles = new LinkedHashMap<>();
        map(result.get("roles")).forEach((role, value) -> {
            Map<?, ?> entry = map(value);
            roles.put(String.valueOf(role), new RoleState(
                    String.valueOf(entry.get("provider")),
                    String.valueOf(entry.get("model")),
                    Boolean.TRUE.equals(entry.get("ready")),
                    entry.get("reason") instanceof String reason ? reason : null));
        });
        return new Diagnostics(
                String.valueOf(core.get("version")),
                String.valueOf(core.get("startId")),
                number(core.get("uptimeSeconds")),
                number(map(result.get("events")).get("lastSeq")),
                (int) number(result.get("clients")),
                (int) number(map(result.get("turns")).get("active")),
                providers,
                roles);
    }

    /**
     * Aviso de "sem provider", ou nada.
     *
     * <p>Se a conversa não tem provider mas a reserva tem, o chat ainda responde —
     * e o aviso diz isso, em vez de assustar com "sem modelo".
     */
    public Optional<String> conversationWarning() {
        RoleState conversation = roles.get("conversation");
        if (conversation != null && conversation.ready()) {
            return Optional.empty();
        }
        String reason = conversation == null ? "nenhum papel 'conversation' configurado" : conversation.reason();
        RoleState fallback = roles.get("fallback");
        if (fallback != null && fallback.ready()) {
            return Optional.of("Respondendo pela reserva (" + fallback.provider() + "): " + reason);
        }
        return Optional.of("Sem modelo para responder: " + reason
                + ". Veja docs/operations/quickstart.md, passo 4.");
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static long number(Object value) {
        return value instanceof Number typed ? typed.longValue() : 0L;
    }
}
