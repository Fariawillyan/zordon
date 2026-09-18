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
package zordon.core.chat;

/**
 * O que o roteador decidiu fazer com a entrada do usuário.
 *
 * <p>Uma rota rápida só pode produzir uma resposta imediata ou um cancelamento —
 * ações GREEN por construção. Assim que uma rota puder tocar o sistema, ela passa
 * pelo caminho normal e pelo motor de permissão, nunca por aqui
 * ([Core §2](../../../../../docs/specs/core/design.md#2-intent-router)).
 */
public sealed interface Intent {

    /** Respondida localmente, sem chamar modelo: custo zero, latência de milissegundos. */
    record Immediate(String answer, String rule) implements Intent {}

    /** Cancela o turno em andamento. */
    record CancelCurrent(String rule) implements Intent {}

    /** Vai para o modelo. */
    record Model(String agentId) implements Intent {}
}
