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

    /** O Zordon não apaga (ADR-0015); a resposta oferece o caminho reversível (SPEC-016 CA-4). */
    public static final String NO_DELETE = "Eu não apago arquivos: nada do que eu faço é sem volta. "
            + "Posso mover para a quarentena, que dá para desfazer depois.";

    private static final Pattern QUARANTINE = Pattern.compile(
            "^(?:(?:ok |ei |oi )?zordon[,:]?\\s*)?"
                    + "(?:mova|mover|move|coloque|colocar|ponha|p[oô]r|mande|manda)\\s+"
                    + "(?:(?:para|pra)\\s+(?:a\\s+)?|(?:na|em)\\s+)quarentena\\s+(?:o |a |os |as )?"
                    + "(?<path>\\S.*?)[.!]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** "lembre que…", "anote que…": o usuário mandando guardar (SPEC-021 CA-2). Pergunta não entra. */
    private static final Pattern REMEMBER = Pattern.compile(
            "^(?:(?:ok |ei |oi )?zordon[,:]?\\s*)?"
                    + "(?:lembre(?:-se)?|anote|anota|guarde|guarda|memorize|registre)\\s+(?:(?:de|disso)\\s+)?"
                    + "(?:que\\s+|:\\s*)(?<content>[^?]*[^?.!\\s])[.!]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** "planeje e faça: …": vira um plano durável e verificado (SPEC-023 CA-1). */
    private static final Pattern PLAN = Pattern.compile(
            "^(?:(?:ok |ei |oi )?zordon[,:]?\\s*)?"
                    + "(?:planeje e (?:fa[cç]a|execute)|fa[cç]a passo a passo|execute passo a passo)\\s*[:,]?\\s+"
                    + "(?<goal>\\S.*?)[.!]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** "planeje a mudança: …" monta o preflight de nove passos (SPEC-029 CA-1). */
    private static final Pattern CHANGE = Pattern.compile(
            "^(?:(?:ok |ei |oi )?zordon[,:]?\\s*)?"
                    + "(?:planeje|planejar|monte|montar|prepare|preparar) (?:a |uma )?(?:mudan[çc]a|altera[çc][ãa]o)"
                    + "\\s*[:,]?\\s+(?<goal>\\S.*?)[.!]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Abrir "o projeto de ontem" é pergunta à memória, não nome de aplicativo (SPEC-021 CA-7). */
    private static final Pattern FROM_MEMORY = Pattern.compile(
            ".*\\b(ontem|anteontem|hoje|semana|ultimo|ultima|de antes|que (a gente |nos |eu )?"
                    + "(trabalh|mex|us|abri|fiz|fizemos)\\w*)\\b.*");

    /** "pergunta pro developer: …" e "developer, …" escolhem o agente (SPEC-022 CA-1). */
    private static final Pattern ASK_AGENT = Pattern.compile(
            "^(?:pergunt[ae]|peca|pede|manda|mande|passa|passe)\\s+(?:pro|pra|para o|para a|ao|a)\\s+"
                    + "(?<agent>[a-z0-9-]+)\\s*[:,]?\\s+(?<task>.+)$");
    private static final Pattern ADDRESS = Pattern.compile("^(?<agent>[a-z0-9-]+)\\s*[,:]\\s+(?<task>.+)$");

    private static final Pattern OPEN = Pattern.compile(
            "^(abra|abre|abrir|inicie|inicia|iniciar|execute|executa|executar|rode|roda) "
                    + "(o |a |os |as |um |uma )?(?<app>.+)$");
    private static final Pattern DELETE = Pattern.compile(
            "^(apague|apaga|apagar|delete|deleta|deletar|exclua|exclui|excluir|remova|remove|remover|"
                    + "limpe|limpa|limpar|destrua|elimine|elimina)\\b.*$");

    private record FastRoute(Pattern pattern, String name) {}

    private static final List<FastRoute> ROUTES = List.of(
            new FastRoute(Pattern.compile("^(que horas sao|que horas|horas)\\??$"), "hora"),
            new FastRoute(Pattern.compile("^(pausa|para|parar|cancela|cancelar|chega)\\.?$"), "cancelar"));

    private final Clock clock;
    private volatile java.util.function.Predicate<String> knownAgent = id -> false;

    /** Quem o roteador reconhece como agente; sem isto, ninguém (SPEC-022). */
    public IntentRouter knowAgents(java.util.function.Predicate<String> known) {
        this.knownAgent = java.util.Objects.requireNonNull(known, "known");
        return this;
    }

    public IntentRouter() {
        this(Clock.systemDefaultZone());
    }

    public IntentRouter(Clock clock) {
        this.clock = clock;
    }

    public Intent route(String input) {
        // O caminho sai do texto original: no WSL, maiúsculas importam (SPEC-017 CA-4).
        java.util.regex.Matcher quarantine = QUARANTINE.matcher(input.strip());
        if (quarantine.matches()) {
            return new Intent.Tool("fs.quarantine", java.util.Map.of("path", quarantine.group("path").strip()),
                    "quarentena");
        }
        java.util.regex.Matcher remember = REMEMBER.matcher(input.strip());
        if (remember.matches()) {
            return new Intent.Tool("memory.remember", java.util.Map.of("content", remember.group("content").strip()),
                    "lembrar");
        }
        java.util.regex.Matcher change = CHANGE.matcher(input.strip());
        if (change.matches()) {
            return new Intent.Tool("change.plan", java.util.Map.of("goal", change.group("goal").strip()), "planejar-mudanca");
        }
        java.util.regex.Matcher plan = PLAN.matcher(input.strip());
        if (plan.matches()) {
            return new Intent.Tool("task.create", java.util.Map.of("goal", plan.group("goal").strip()), "planejar");
        }
        String normalized = normalize(input);
        if (DELETE.matcher(normalized).matches()) {
            return new Intent.Immediate(NO_DELETE, "sem-exclusao");
        }
        for (Pattern pattern : List.of(ASK_AGENT, ADDRESS)) {
            java.util.regex.Matcher agent = pattern.matcher(normalized);
            if (agent.matches() && knownAgent.test(agent.group("agent"))) {
                return new Intent.Model(agent.group("agent").replaceFirst("agent$", ""));
            }
        }
        java.util.regex.Matcher open = OPEN.matcher(normalized);
        if (open.matches() && !FROM_MEMORY.matcher(open.group("app")).matches()) {
            return new Intent.Tool("app.open", java.util.Map.of("name", open.group("app")), "abrir");
        }
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
        // A transcrição da voz termina frases com ponto: "Que horas são." (SPEC-011 CA-8).
        text = text.replaceAll("[.!…]+$", "");
        return text.replaceAll("\\s+", " ").trim();
    }
}
