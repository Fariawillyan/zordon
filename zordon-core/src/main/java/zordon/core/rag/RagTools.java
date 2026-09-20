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
package zordon.core.rag;

import java.util.List;
import java.util.Map;
import java.util.Set;
import zordon.api.security.ActionDescriptor;
import zordon.api.security.Effect;
import zordon.api.security.RiskLevel;
import zordon.api.trace.Spec;
import zordon.core.tools.Tool;
import zordon.core.tools.ToolException;
import zordon.core.tools.ToolResult;
import zordon.security.Gatekeeper;

/** {@code rag.search}: o que a documentação do projeto diz, com a citação junto (SPEC-028). */
@Spec("SPEC-028")
public final class RagTools {

    public static Tool search(KnowledgeBase knowledge) {
        return new Tool() {
            @Override public String name() { return "rag.search"; }

            @Override
            public String description() {
                return "Procura na documentação do projeto (SPECs, ADRs, arquitetura, segurança) e devolve os"
                        + " trechos com arquivo e seção para citar.";
            }

            @Override public RiskLevel baseRisk() { return RiskLevel.GREEN; }
            @Override public Set<Effect> effects() { return Set.of(); }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of(
                        "query", Map.of("type", "string", "description", "o que procurar na documentação"),
                        "limit", Map.of("type", "integer", "description", "quantos trechos, até 8")),
                        "required", List.of("query"));
            }

            @Override
            public ActionDescriptor describe(Map<String, Object> args) throws ToolException {
                Tool.text(args, "query");
                return new ActionDescriptor(name(), args, RiskLevel.GREEN, Set.of(), List.of(), 0, List.of(),
                        "Procurar na documentação");
            }

            @Override
            public ToolResult run(Gatekeeper.Permit.Granted permit, Map<String, Object> args) throws Exception {
                String query = Tool.text(args, "query");
                int limit = args.get("limit") instanceof Number number ? number.intValue() : KnowledgeBase.MAX_HITS;
                return ToolResult.of(knowledge.answerContext(query, limit));
            }
        };
    }

    private RagTools() {}
}
