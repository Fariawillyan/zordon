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
package zordon.api.event;

import java.util.Set;

/**
 * Tópicos do barramento (docs/architecture/event-driven.md §5). Clientes assinam
 * tópicos, não eventos individuais.
 */
public final class Topic {

    public static final String CHAT = "chat";
    public static final String VOICE = "voice";
    public static final String TOOLS = "tools";
    public static final String AGENTS = "agents";
    public static final String MCP = "mcp";
    public static final String PERMISSION = "permission";
    public static final String SECURITY = "security";
    public static final String SYSTEM = "system";
    public static final String AUTOMATION = "automation";
    public static final String MEMORY = "memory";
    public static final String RAG = "rag";
    public static final String CHANGE = "change";

    public static final Set<String> ALL =
            Set.of(CHAT, VOICE, TOOLS, AGENTS, MCP, PERMISSION, SECURITY, SYSTEM, AUTOMATION, MEMORY, RAG, CHANGE);

    /**
     * Tópicos que um cliente não pode desassinar: recusar evento de segurança ou de
     * alteração de projeto quebraria a invariante de nenhuma iniciativa silenciosa
     * (ADR-0014, ADR-0024).
     */
    public static final Set<String> MANDATORY = Set.of(SECURITY, CHANGE);

    public static boolean exists(String topic) {
        return ALL.contains(topic);
    }

    private Topic() {}
}
