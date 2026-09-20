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
 * Para onde o composer envia, dito antes do envio
 * ([Layout §4](../../../../../../../docs/specs/ui/desktop-layout.md#composer-global)).
 *
 * @param newConversation verdadeiro quando o envio cria uma conversa nova
 * @param label o texto exibido acima do campo
 */
public record ComposerTarget(boolean newConversation, String label) {

    /**
     * Em Chat, para a conversa visível. Em qualquer outra tela, para uma conversa
     * nova — mandar para uma conversa que o usuário não está vendo seria enviar às
     * cegas.
     */
    public static ComposerTarget forScreen(Destination screen, boolean hasOpenConversation) {
        if (screen == Destination.CHAT && hasOpenConversation) {
            return new ComposerTarget(false, "Enviar para: conversa atual");
        }
        if (screen == Destination.CHAT) {
            return new ComposerTarget(true, "Enviar para: nova conversa");
        }
        return new ComposerTarget(true, "Enviar para: nova conversa no Chat");
    }
}
