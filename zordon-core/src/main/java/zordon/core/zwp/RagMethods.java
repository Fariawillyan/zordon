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
import java.util.Objects;
import zordon.api.trace.Spec;
import zordon.api.zwp.ClientKind;
import zordon.api.zwp.ZwpErrorKind;
import zordon.core.rag.KnowledgeBase;

/** {@code rag.status}, {@code rag.search} e {@code rag.reindex} (SPEC-028). */
@Spec("SPEC-028")
public final class RagMethods {

    private final KnowledgeBase knowledge;

    public RagMethods(KnowledgeBase knowledge) {
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    }

    public void registerOn(ZwpServer server) {
        server.register("rag.status", (session, params) -> knowledge.status())
                .register("rag.search", (session, params) -> {
                    if (!(params.get("query") instanceof String query) || query.isBlank()) {
                        throw new ZwpMethodException(ZwpErrorKind.ERR_INVALID_ARGUMENT, "query é obrigatório");
                    }
                    return Map.of("hits", knowledge.search(query,
                            params.get("limit") instanceof Number limit ? limit.intValue() : 8).stream()
                            .map(hit -> Map.of("path", hit.path(), "heading", hit.heading(), "text", hit.text(),
                                    "score", hit.score())).toList());
                })
                .register("rag.reindex", (session, params) -> {
                    if (!session.is(ClientKind.DESKTOP)) {
                        throw new ZwpMethodException(ZwpErrorKind.ERR_PERMISSION_DENIED,
                                "só a janela do Zordon reindexa");
                    }
                    KnowledgeBase.Indexed indexed = knowledge.reindex();
                    return Map.of("files", indexed.files(), "chunks", indexed.chunks(), "tookMs", indexed.tookMs());
                });
    }
}
