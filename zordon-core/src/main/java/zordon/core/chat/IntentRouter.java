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
package zordon.core.chat;

import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decide o que fazer com cada entrada do usuário.
 *
 * <p>Existe por dois motivos: latência — a maioria dos comandos não precisa de um
 * modelo grande — e custo: não pagar Opus para "que horas são".
 *
 * <p>Neste marco só há a rota rápida e o agente geral. A classificação por modelo
 * pequeno entra quando houver mais de um agente para escolher (M5).
 */
public final class IntentRouter {

    /** Agente único enquanto não há outros. Sem ele, intenção não classificada morre sem resposta. */
    public static final String GENERAL_AGENT = "zordon";

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH'h'mm");

    private record FastRoute(Pattern pattern, String name) {}

    private static final List<FastRoute> ROUTES = List.of(
            new FastRoute(Pattern.compile("^(que horas sao|que horas|horas)\\??$"), "hora"),
            new FastRoute(Pattern.compile("^(pausa|para|parar|cancela|cancelar|chega)\\.?$"), "cancelar"));

    private final Clock clock;

    public IntentRouter() {
        this(Clock.systemDefaultZone());
    }

    public IntentRouter(Clock clock) {
        this.clock = clock;
    }

    public Intent route(String input) {
        String normalized = normalize(input);
        for (FastRoute route : ROUTES) {
            if (route.pattern().matcher(normalized).matches()) {
                return switch (route.name()) {
                    case "hora" -> new Intent.Immediate("São " + LocalTime.now(clock).format(HOUR) + ".", "hora");
                    case "cancelar" -> new Intent.CancelCurrent("cancelar");
                    default -> new Intent.Model(GENERAL_AGENT);
                };
            }
        }
        return new Intent.Model(GENERAL_AGENT);
    }

    /**
     * Minúsculas, sem acento e sem a palavra de ativação.
     *
     * <p>Normalizar antes de casar é o que faz "Zordon, que horas são?" e "que
     * horas sao" caírem na mesma rota.
     */
    static String normalize(String input) {
        String text = input.trim().toLowerCase(Locale.ROOT);
        text = text.replaceFirst("^(ok |ei |oi )?zordon[,:]?\\s*", "");
        text = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return text.replaceAll("\\s+", " ").trim();
    }
}
