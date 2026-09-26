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

import zordon.desktop.shell.ComposerTarget;

/** Contratos de ações da tela, agrupados por superfície para manter o shell pequeno. */
public interface ShellActions extends ShellConversationActions, ShellVoiceActions, ShellSecurityActions, ShellDataActions {

    void send(String text, ComposerTarget target);
    void newConversation();
    void cancelTurn(String turnId);
    void refreshDiagnostics();

}
