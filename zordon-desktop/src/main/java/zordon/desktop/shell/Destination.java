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
    HOME(Group.WORK, "Painel", "grid", null),
    CHAT(Group.WORK, "Conversa", "doc", null),
    VOICE(Group.WORK, "Voz", "wave", null),
    AGENTS(Group.RESOURCES, "Agentes", "chip", "M5"),
    MCP(Group.RESOURCES, "MCP", "plug", "M4"),
    SKILLS(Group.RESOURCES, "Skills", "tool", "M3"),
    AUTOMATIONS(Group.RESOURCES, "Automações", "clock", "M6"),
    MEMORY(Group.RESOURCES, "Memória", "brain", "M5"),
    KNOWLEDGE(Group.RESOURCES, "Conhecimento", "book", "M8"),
    SYSTEM(Group.OPERATION, "Sistema", "cpu", "M3"),
    USAGE(Group.OPERATION, "Uso", "coin", "M8"),
    LOGS(Group.OPERATION, "Logs", "list", null),
    SECURITY(Group.OPERATION, "Segurança", "shield", "M3"),
    DIAGNOSTICS(Group.OPERATION, "Diagnóstico", "pulse", null),
    SETTINGS(Group.FOOTER, "Ajustes", "settings", null);

    /** Os ícones do trilho, na ordem da imagem de referência (SPEC-010 §3). */
    public static final List<Destination> RAIL = List.of(VOICE, HOME, CHAT, SETTINGS);

    /** Grupos da navegação, com o rótulo exibido. */
    public enum Group {
        WORK("TRABALHO"),
        RESOURCES("RECURSOS"),
        OPERATION("OPERAÇÃO"),
        FOOTER("");

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
        if (this == SETTINGS) {
            return "Ainda sem tela: edite ~/.zordon/config.toml e reinicie o núcleo.";
        }
        return label + " chega no " + pendingUntil + ".";
    }

    /** Qual ícone do trilho acende: Logs e Diagnóstico abrem pelo Painel. */
    public Destination railOwner() {
        return RAIL.contains(this) ? this : HOME;
    }

    public static List<Destination> inGroup(Group group) {
        return Arrays.stream(values()).filter(destination -> destination.group == group).toList();
    }
}
