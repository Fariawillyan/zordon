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
 * Modo de raciocínio pedido ao modelo.
 *
 * <p>Só existe {@code ADAPTIVE} e {@code OFF} porque o orçamento explícito de
 * pensamento foi removido nos modelos da geração atual — mandá-lo devolve 400.
 * O adaptador traduz isto para a forma correta de cada modelo.
 */
public enum Thinking {
    ADAPTIVE,
    OFF
}
