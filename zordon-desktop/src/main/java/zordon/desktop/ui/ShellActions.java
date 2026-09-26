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

/**
 * O que o shell pede a quem fala com o núcleo. A tela nunca chama o protocolo
 * direto: é isso que permite exercitá-la sem núcleo nenhum.
 *
 * <p>Aqui ficam só as da conversa; as outras vêm agrupadas pela tela que as usa,
 * como o estado: voz, segurança, memória e o trabalho (MCP, tarefas, automações,
 * agentes, uso e sistema).
 */
public interface ShellActions {

    void send(String text, ComposerTarget target);

    void newConversation();

    void cancelTurn(String turnId);

    void refreshDiagnostics();

    /** A voz não tem padrão: um botão de voz que não faz nada seria defeito silencioso. */
    ShellVoiceActions voice();

    default ShellSecurityActions security() {
        return ShellSecurityActions.NONE;
    }

    default ShellMemoryActions memory() {
        return ShellMemoryActions.NONE;
    }

    default ShellDataActions data() {
        return ShellDataActions.NONE;
    }
}
