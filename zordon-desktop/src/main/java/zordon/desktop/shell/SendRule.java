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
 * Quando uma tecla envia a mensagem
 * ([Layout §4](../../../../../../../docs/specs/ui/desktop-layout.md#composer-global)).
 *
 * <p>Separado da tela para ser testável: é uma regra, e uma regra errada aqui manda
 * mensagem pela metade.
 */
public final class SendRule {

    private SendRule() {}

    /**
     * @param composing o usuário está no meio de uma composição de IME — Enter ali
     *     confirma o caractere, não a mensagem
     */
    public static boolean shouldSend(boolean enter, boolean shift, boolean composing, String text) {
        return enter && !shift && !composing && text != null && !text.isBlank();
    }
}
