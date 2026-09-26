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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import zordon.api.trace.Spec;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.defense.DefenseEngine;
import zordon.core.defense.DefenseService;
import zordon.core.mcp.McpManager;

/** Disjuntores, eventos e achados da defesa (SPEC-026, SPEC-027). */
@Spec("SPEC-027")
public final class DefenseMethods {

    private final DefenseEngine response;
    private final DefenseService defense;
    private final McpManager mcp;

    public DefenseMethods(DefenseEngine response, DefenseService defense, McpManager mcp) {
        this.response = Objects.requireNonNull(response, "response");
        this.defense = Objects.requireNonNull(defense, "defense");
        this.mcp = Objects.requireNonNull(mcp, "mcp");
    }

    public void registerOn(ZwpServer server) {
        server.register("security.breakers", (session, params) -> Map.of("breakers", response.breakers()))
                .register("security.events", (session, params) -> Map.of("events", response.events(
                        params.get("limit") instanceof Number limit ? limit.intValue() : 50)))
                .register("security.breakerRelease", (session, params) -> release(session, params));
        server.register("security.findings", (session, params) -> Map.of("findings", defense.findings(
                params.get("since") instanceof String since ? Instant.parse(since) : null,
                params.get("severities") instanceof List<?> severities
                        ? severities.stream().map(String::valueOf).toList() : List.of(),
                params.get("limit") instanceof Number limit ? limit.intValue() : 50)))
                .register("security.findingAcknowledge", (session, params) -> acknowledge(session, params));
    }

    /** Um disjuntor só fecha por decisão do usuário, e só pela janela (SPEC-027). */
    private Map<String, Object> release(ZwpSession session, Map<String, Object> params) {
        if (!session.is(ClientKind.DESKTOP)) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_PERMISSION_DENIED,
                    "só a janela do Zordon libera um disjuntor");
        }
        if (!(params.get("subject") instanceof String subject) || subject.isBlank()) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "subject é obrigatório");
        }
        String mode = params.get("mode") instanceof String given ? given : "supervised";
        String state = response.release(subject, mode).orElseThrow(() ->
                new ZwpMethodException(ZwpErrorKind.ERR_NOT_FOUND, "não há disjuntor aberto para " + subject));
        if (subject.startsWith("mcp:")) {
            mcp.release(subject.substring("mcp:".length()));
        }
        return Map.of("state", state);
    }

    private Map<String, Object> acknowledge(ZwpSession session, Map<String, Object> params) {
        if (!session.is(ClientKind.DESKTOP)) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_PERMISSION_DENIED,
                    "só a janela do Zordon confirma um achado");
        }
        if (!(params.get("findingId") instanceof String findingId) || findingId.isBlank()) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "findingId é obrigatório");
        }
        return Map.of("acknowledged", defense.acknowledge(findingId));
    }
}
