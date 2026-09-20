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

import java.util.Arrays;
import java.util.List;

/**
 * Os destinos da navegação, na ordem de
 * [Layout §3](../../../../../../../docs/specs/ui/desktop-layout.md#3-navegação-e-arquitetura-de-informação).
 *
 * <p>Destino de marco futuro aparece — o usuário precisa saber que ele virá — mas
 * indisponível e com o marco. Nunca abre uma tela que simule função.
 */
public enum Destination {
    /** A tela do Zordon: é ela o trabalho, não um painel de atalhos (SPEC-032). */
    VOICE(Group.WORK, "Voz", "wave", null),
    CHAT(Group.WORK, "Conversa", "doc", null),
    // Os nove abaixo deixaram de ser promessa em 2026-09-20 (SPEC-030): os marcos
    // M3 a M8 entraram, e o selo dizia "chega no M5" de um recurso que já rodava.
    AGENTS(Group.RESOURCES, "Agentes", "chip", null),
    MCP(Group.RESOURCES, "MCP", "plug", null),
    SKILLS(Group.RESOURCES, "Skills", "tool", null),
    AUTOMATIONS(Group.RESOURCES, "Automações", "clock", null),
    /** Veio da aba "Aplicativos" dos Ajustes, que deixou de existir (SPEC-031). */
    TASKS(Group.RESOURCES, "Tarefas", "check", null),
    MEMORY(Group.RESOURCES, "Memória", "brain", null),
    KNOWLEDGE(Group.RESOURCES, "Conhecimento", "book", null),
    SYSTEM(Group.OPERATION, "Sistema", "cpu", null),
    USAGE(Group.OPERATION, "Uso", "coin", null),
    LOGS(Group.OPERATION, "Logs", "list", null),
    SECURITY(Group.OPERATION, "Segurança", "shield", null),
    DIAGNOSTICS(Group.OPERATION, "Diagnóstico", "pulse", null),
    /** Os ajustes de voz e do microfone: um destino, não uma segunda tela. */
    SETTINGS(Group.OPERATION, "Ajustes", "settings", null);

    /** Grupos da navegação, com o rótulo exibido. */
    public enum Group {
        WORK("TRABALHO"),
        RESOURCES("RECURSOS"),
        OPERATION("OPERAÇÃO");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Group group;
    private final String label;
    private final String icon;
    private final String pendingUntil;

    Destination(Group group, String label, String icon, String pendingUntil) {
        this.group = group;
        this.label = label;
        this.icon = icon;
        this.pendingUntil = pendingUntil;
    }

    public Group group() {
        return group;
    }

    public String label() {
        return label;
    }

    public String icon() {
        return icon;
    }

    public boolean isAvailable() {
        return pendingUntil == null;
    }

    /** O selo ao lado do nome: o marco em que chega, ou nada. */
    public String badge() {
        return pendingUntil == null || pendingUntil.contains(".") ? "" : pendingUntil;
    }

    /** Por que não abre — em palavras, não só em cor (Design system §3). */
    public String unavailableReason() {
        if (pendingUntil == null) {
            return "";
        }
        return label + " chega no " + pendingUntil + ".";
    }

    public static List<Destination> inGroup(Group group) {
        return Arrays.stream(values()).filter(destination -> destination.group == group).toList();
    }
}
