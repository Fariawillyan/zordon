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
        // Nenhum destino está pendente hoje (SPEC-030): a regra continua valendo, e
        // é provada com um destino inventado, para não voltar a mentir sobre um real.
        Destination futuro = Destination.MCP;

        assertThat(futuro.isAvailable()).as("o MCP chegou no M4 e a tela precisa abrir").isTrue();
        assertThat(state.select(futuro)).isTrue();
        assertThat(state.destinationProperty().get()).isEqualTo(futuro);
    }

    @AcceptanceCriteria("SPEC-030/CA-1")
    @Test
    void oMecanismoDeMarcoFuturoContinuaDeEpeENaoSobrouNinguemNele() {
        // O mecanismo tem de continuar existindo para o próximo destino que não
        // existir ainda — mas hoje ninguém pode estar nele, porque M3 a M8 entraram.
        assertThat(Arrays.stream(Destination.values()).filter(destination -> !destination.isAvailable()))
                .as("destino ainda prometido").isEmpty();
        assertThat(Arrays.stream(Destination.values()).map(Destination::badge))
                .as("selo de marco sobrando").allMatch(String::isEmpty);
    }

    @AcceptanceCriteria("SPEC-005/CA-2")
    @Test
    void soAsTelasDesteMarcoEstaoDisponiveis() {
        // Desde a SPEC-030, todo destino do Painel abre: os nove que restavam
        // ganharam tela em 2026-09-20.
        assertThat(Arrays.stream(Destination.values()).filter(Destination::isAvailable))
                .containsExactly(Destination.values());
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
