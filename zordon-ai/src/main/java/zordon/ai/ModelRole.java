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
package zordon.ai;

/**
 * Papel de um modelo na operação do Zordon.
 *
 * <p>O provider é configurado por papel, não globalmente: trocar o modelo da
 * conversa não deve arrastar o do roteamento nem o dos embeddings
 * ([Core §1](../../../../docs/specs/core/design.md#1-abstração-de-provider)).
 */
public enum ModelRole {
    CONVERSATION,
    ROUTING,
    AGENT_HEAVY,
    AGENT_LIGHT,
    SUMMARIZE,
    EMBEDDINGS,
    FALLBACK
}
