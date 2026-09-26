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

/** Segurança: avisos, só leitura, OPPRESSOR MODE, quarentena, achados e disjuntores. */
public interface ShellSecurityActions {

    /** Nada: para quem só mostra a tela, como os testes. */
    ShellSecurityActions NONE = new ShellSecurityActions() { };

    /** O usuário viu o aviso: {@code notify.acknowledge} (SPEC-015). */
    default void acknowledge(String messageId) {}

    /** "Pausar Zordon": só leitura até retomar pela tela (SPEC-015). */
    default void pauseZordon() {}

    /** Sai do só leitura. Só a tela faz isso. */
    default void resumeZordon() {}

    /**
     * Entra no OPPRESSOR MODE com a senha mestre (SPEC-036). Só a tela faz isso.
     *
     * <p>A implementação zera {@code password} depois de montar o pedido.
     */
    default void enterOppressor(char[] password) {}

    /** Sai do OPPRESSOR MODE, de volta ao comportamento normal (SPEC-036). */
    default void exitOppressor() {}

    /**
     * Cadastra ou troca a senha mestre (SPEC-036). {@code current} é vazio no
     * primeiro cadastro. A implementação zera os dois vetores depois do pedido.
     */
    default void setOppressorPassword(char[] current, char[] next) {}

    /** {@code security.quarantine.list} (SPEC-017). */
    default void loadQuarantine() {}

    /** Devolve um item da quarentena, pelo caminho mediado. */
    default void restoreQuarantine(String vaultId) {}

    /** {@code security.findings} (SPEC-026). */
    default void loadFindings() {}

    /** Confirma que leu um achado. Só a tela faz isso. */
    default void acknowledgeFinding(String findingId) {}

    /** Libera um disjuntor: {@code supervised} ou {@code closed}. Só a tela faz isso. */
    default void releaseBreaker(String subject, String mode) {}

    /** {@code security.events} (SPEC-027): o que foi feito, e por quê. */
    default void loadSecurityEvents() {}
}
