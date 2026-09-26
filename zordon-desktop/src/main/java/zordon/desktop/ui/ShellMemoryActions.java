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
package zordon.desktop.ui;

/** A memória e a base de conhecimento (SPEC-021, SPEC-028). */
public interface ShellMemoryActions {

    /** Nada: para quem só mostra a tela, como os testes. */
    ShellMemoryActions NONE = new ShellMemoryActions() { };

    /** {@code memory.facts} (SPEC-021). */
    default void loadMemory() {}

    /** Esquece um fato de verdade. Só a tela faz isso. */
    default void forgetFact(String factId) {}

    /** {@code rag.status} e {@code rag.roots} (SPEC-028). */
    default void loadKnowledge() {}

    /** {@code rag.reindex} (SPEC-028): só pela tela, e só quando o usuário manda. */
    default void reindexKnowledge() {}
}
