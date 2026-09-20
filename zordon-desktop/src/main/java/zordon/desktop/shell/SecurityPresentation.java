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

import java.util.List;
import java.util.Map;
import zordon.api.trace.Spec;

/**
 * O que o diálogo de permissão e os avisos mostram, já decidido (SPEC-015).
 * Puro: as regras de segurança da tela são testadas sem toolkit.
 */
@Spec("SPEC-015")
public final class SecurityPresentation {

    public static final String PAUSED = "Zordon pausado — só leitura";

    /** Um pedido de {@code ui.requestPermission}, pronto para a tela. */
    public record Prompt(
            String requestId,
            String title,
            String summary,
            String risk,
            String origin,
            List<String> targets,
            int hiddenTargets,
            boolean requiresCheck,
            boolean offersSession,
            int seconds) {}

    private SecurityPresentation() {}

    public static Prompt prompt(Map<String, Object> params) {
        String risk = String.valueOf(params.getOrDefault("risk", "red"));
        String origin = String.valueOf(params.getOrDefault("origin", "ui"));
        boolean perAction = !Boolean.FALSE.equals(params.get("perAction"));
        List<String> targets = params.get("targets") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        int count = params.get("targetCount") instanceof Number number ? number.intValue() : targets.size();
        long ttl = params.get("ttlMs") instanceof Number number ? number.longValue() : 60_000;
        boolean red = !"yellow".equals(risk) && !"green".equals(risk);
        return new Prompt(
                String.valueOf(params.getOrDefault("requestId", "")),
                red ? "Autorizar ação de risco alto?" : "Autorizar esta ação?",
                String.valueOf(params.getOrDefault("summary", "")),
                red ? "Risco alto (RED): confira cada alvo" : "Risco moderado (YELLOW)",
                originLabel(origin),
                targets,
                Math.max(0, count - targets.size()),
                // RED: autorizar exige uma ação positiva distinta, não o botão em foco (Segurança §2).
                red,
                // "Nesta sessão" só para YELLOW pedido pela janela; nunca para RED nem para voz.
                !red && !perAction && "ui".equals(origin),
                (int) Math.max(1, Math.round(ttl / 1000.0)));
    }

    public static String originLabel(String origin) {
        return switch (origin) {
            case "voice" -> "Pedido por voz — só vale a decisão na tela";
            case "automation" -> "Pedido por automação";
            case "agent" -> "Pedido por um agente";
            case "autonomous" -> "Pedido pela defesa";
            default -> "Pedido pela janela";
        };
    }

    /** O que o diálogo devolve ao núcleo. Fechar, Esc, Enter ou o tempo acabar é negar. */
    public enum Choice { DENY, ONCE, SESSION }

    public static String answer(Choice choice) {
        return switch (choice) {
            case DENY -> "deny";
            case ONCE -> "once";
            case SESSION -> "session";
        };
    }

    /** WARNING e acima viram banner na janela (Comunicação §3). */
    public static boolean banner(String severity) {
        return !"info".equals(severity);
    }

    /** CRITICAL abre a janela à força e exige confirmação de leitura (Comunicação §3). */
    public static boolean critical(String severity) {
        return "critical".equals(severity);
    }

    /** Uma linha para o banner: título e o que foi feito. */
    public static String bannerText(Map<String, Object> message) {
        return message.getOrDefault("title", "") + " — " + message.getOrDefault("actionTaken", "");
    }
}
