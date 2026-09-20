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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import zordon.api.trace.Spec;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.ZwpErrorKind;
import zordon.memory.Fact;
import zordon.memory.FactKind;
import zordon.memory.MemoryStore;
import zordon.memory.RecallQuery;

/** Ver, procurar, exportar e esquecer (SPEC-021, Memória §8). Esquecer é só da tela. */
@Spec("SPEC-021")
public final class MemoryMethods {

    private final MemoryStore store;

    public MemoryMethods(MemoryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public void registerOn(ZwpServer server) {
        server.register("memory.facts", (session, params) -> {
            FactKind kind = null;
            if (params.get("kind") instanceof String given && !given.isBlank()) {
                try {
                    kind = FactKind.valueOf(given.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "kind desconhecido: " + given);
                }
            }
            String subject = params.get("subject") instanceof String given ? given : null;
            int limit = params.get("limit") instanceof Number number ? number.intValue() : 100;
            return Map.of("facts", store.facts(kind, subject, limit).stream().map(MemoryMethods::wire).toList());
        }).register("memory.search", (session, params) -> {
            if (!(params.get("query") instanceof String query) || query.isBlank()) {
                throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "query é obrigatório");
            }
            int limit = params.get("limit") instanceof Number number ? number.intValue() : 10;
            return Map.of("hits", store.search(RecallQuery.of(query, limit)).stream()
                    .map(hit -> Map.of("fact", wire(hit.fact()), "score", hit.score())).toList());
        }).register("memory.forget", (session, params) -> {
            desktopOnly(session);
            if (!(params.get("factId") instanceof String id) || id.isBlank()) {
                throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "factId é obrigatório");
            }
            return Map.of("forgotten", store.forget(id));
        }).register("memory.forgetSubject", (session, params) -> {
            desktopOnly(session);
            if (!(params.get("subject") instanceof String subject) || subject.isBlank()) {
                throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "subject é obrigatório");
            }
            return Map.of("forgotten", store.forgetSubject(subject));
        }).register("memory.export", (session, params) -> Map.of(
                "facts", store.facts(null, null, 500).stream().map(MemoryMethods::wire).toList(),
                "stats", store.stats()));
    }

    /** Esquecer é decisão do dono, na tela: voz, host e automação não esquecem (SPEC-021 CA-6). */
    private static void desktopOnly(ZwpSession session) {
        if (!session.is(ClientKind.DESKTOP)) {
            throw new ZwpMethodException(ZwpErrorKind.ERR_PERMISSION_DENIED, "só a janela do Zordon apaga lembranças");
        }
    }

    static Map<String, Object> wire(Fact fact) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", fact.id());
        out.put("kind", fact.kind().name());
        out.put("subject", fact.subject());
        out.put("content", fact.content());
        out.put("confidence", fact.confidence());
        out.put("observedAt", fact.observedAt().toString());
        out.put("provenance", fact.provenance());
        out.put("source", fact.source());
        out.put("accessCount", fact.accessCount());
        if (fact.expiresAt() != null) {
            out.put("expiresAt", fact.expiresAt().toString());
        }
        return out;
    }
}
