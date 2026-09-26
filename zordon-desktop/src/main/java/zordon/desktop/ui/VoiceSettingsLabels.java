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

/** Texto dos estados mostrados nas seções de segurança, tarefas e memória. */
final class VoiceSettingsLabels {
    private VoiceSettingsLabels() {}

    static String task(String state) {
        return switch (state) {
            case "planned" -> "planejada";
            case "running" -> "em andamento";
            case "verifying" -> "conferindo";
            case "done" -> "concluída";
            case "failed" -> "falhou";
            case "waiting_human" -> "espera você";
            case "blocked" -> "bloqueada";
            case "cancelled" -> "cancelada";
            default -> state;
        };
    }

    static String breaker(String state) {
        return switch (state) {
            case "open" -> "aberto (bloqueado)";
            case "half_open" -> "em prova (cada ação confirmada)";
            default -> state;
        };
    }

    static String kind(String kind) {
        return switch (kind) {
            case "PREFERENCE" -> "preferência";
            case "PROJECT" -> "projeto";
            case "EVENT" -> "evento";
            case "PROCEDURE" -> "procedimento";
            default -> "fato";
        };
    }

    static String state(String state) {
        return switch (state) {
            case "connected" -> "conectado";
            case "starting" -> "iniciando";
            case "reconnecting" -> "reconectando";
            case "drift" -> "mudou, aguardando aprovação";
            case "failed" -> "falhou";
            default -> "parado";
        };
    }
}
