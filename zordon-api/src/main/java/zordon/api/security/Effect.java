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
package zordon.api.security;

/**
 * Consequências possíveis de uma ação (docs/api/core-interfaces.md §1).
 *
 * <p>Não existe efeito de exclusão (ADR-0015): a operação destrutiva mais forte
 * do sistema é {@link #QUARANTINE_FS}, que é reversível.
 */
public enum Effect {
    READ_FS,
    WRITE_FS,
    QUARANTINE_FS,
    SPAWN_PROCESS,
    SUSPEND_PROCESS,
    KILL_PROCESS,
    NETWORK,
    NETWORK_BLOCK,
    MODIFY_SYSTEM,
    MODIFY_CREDENTIALS,
    EXPORT_DATA,
    /** Alterar o repositório do próprio Zordon. Sempre em branch, nunca instalado. */
    MODIFY_SELF,
    /** Alterar um projeto do usuário declarado em {@code paths.workspaces}. */
    MODIFY_PROJECT,
    /** Alterar o núcleo de confiança. Sempre RED, nunca aplicado: só proposto (ADR-0024). */
    MODIFY_TRUST_KERNEL
}
