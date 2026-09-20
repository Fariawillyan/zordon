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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import zordon.api.trace.AcceptanceCriteria;

/** As regras puras do shell: destinos e composer. */
class ShellRulesTest {

    @AcceptanceCriteria("SPEC-005/CA-2")
    @Test
    void destinoDeMarcoFuturoTemMarcoEMotivoENaoAbre() {
        DesktopState state = new DesktopState();

        assertThat(Destination.MCP.isAvailable()).isFalse();
        assertThat(Destination.MCP.badge()).isEqualTo("M4");
        assertThat(Destination.MCP.unavailableReason()).isEqualTo("MCP chega no M4.");
        assertThat(state.select(Destination.MCP)).isFalse();
        // A janela abre na Voz (SPEC-010 CA-1) e continua nela.
        assertThat(state.destinationProperty().get()).isEqualTo(Destination.VOICE);
    }

    @AcceptanceCriteria("SPEC-005/CA-2")
    @Test
    void soAsTelasDesteMarcoEstaoDisponiveis() {
        assertThat(Arrays.stream(Destination.values()).filter(Destination::isAvailable))
                .containsExactly(Destination.HOME, Destination.CHAT, Destination.VOICE, Destination.LOGS,
                        Destination.DIAGNOSTICS, Destination.SETTINGS);
    }

    @AcceptanceCriteria("SPEC-005/CA-6")
    @Test
    void emChatOComposerEnviaParaAConversaVisivel() {
        assertThat(ComposerTarget.forScreen(Destination.CHAT, true))
                .isEqualTo(new ComposerTarget(false, "Enviar para: conversa atual"));
    }

    @AcceptanceCriteria("SPEC-005/CA-6")
    @Test
    void foraDoChatOComposerCriaUmaConversaNova() {
        ComposerTarget target = ComposerTarget.forScreen(Destination.LOGS, true);

        // Mandar para uma conversa que o usuário não está vendo seria enviar às cegas.
        assertThat(target.newConversation()).isTrue();
        assertThat(target.label()).isEqualTo("Enviar para: nova conversa no Chat");
    }

    @AcceptanceCriteria("SPEC-005/CA-7")
    @Test
    void enterEnviaMasShiftEnterComposicaoEVazioNao() {
        assertThat(SendRule.shouldSend(true, false, false, "oi")).isTrue();
        assertThat(SendRule.shouldSend(true, true, false, "oi")).isFalse();
        assertThat(SendRule.shouldSend(true, false, true, "oi")).isFalse();
        assertThat(SendRule.shouldSend(true, false, false, "   ")).isFalse();
        assertThat(SendRule.shouldSend(false, false, false, "oi")).isFalse();
    }

    @AcceptanceCriteria("SPEC-005/CA-9")
    @Test
    void quemRolouParaCimaNaoEhPuxadoEVeOAviso() {
        ScrollFollow follow = new ScrollFollow();

        assertThat(follow.onNewContent(false)).isEqualTo(ScrollFollow.Action.SHOW_INDICATOR);
        assertThat(follow.hasPendingContent()).isTrue();

        follow.reachedBottom();
        assertThat(follow.hasPendingContent()).isFalse();
        assertThat(follow.onNewContent(true)).isEqualTo(ScrollFollow.Action.FOLLOW);
    }
}
