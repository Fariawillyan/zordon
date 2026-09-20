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
package zordon.desktop.shell;

/**
 * Quando a conversa acompanha o conteúdo novo, e quando avisa em vez de puxar.
 *
 * <p>Streaming não rouba a leitura: se o usuário rolou para cima, a lista fica onde
 * ele deixou e aparece "Novas mensagens"
 * ([Layout §4](../../../../../../../docs/specs/ui/desktop-layout.md#4-início-e-chat)).
 */
public final class ScrollFollow {

    /** O que fazer com conteúdo novo. */
    public enum Action {
        FOLLOW,
        SHOW_INDICATOR
    }

    private boolean pending;

    public Action onNewContent(boolean atBottom) {
        if (atBottom) {
            pending = false;
            return Action.FOLLOW;
        }
        pending = true;
        return Action.SHOW_INDICATOR;
    }

    /** O usuário chegou ao fim por conta própria, ou clicou no aviso. */
    public void reachedBottom() {
        pending = false;
    }

    public boolean hasPendingContent() {
        return pending;
    }
}
