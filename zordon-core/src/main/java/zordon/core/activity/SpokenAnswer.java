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
package zordon.core.activity;

import java.util.Locale;
import java.util.regex.Pattern;
import zordon.api.trace.Spec;

/**
 * A resposta do modelo virando algo que se ouve (SPEC-034).
 *
 * <p>Duas coisas, e nenhuma delas é opcional
 * ([Comunicação §3](../../../../../../docs/security/communication.md#3-níveis-de-alerta)):
 * a marcação de Markdown não é lida em voz alta — ninguém quer ouvir "asterisco
 * asterisco" —, e a voz diz o <b>resumo</b>, apontando para a tela, porque texto
 * longo falado não é absorvido.
 */
@Spec("SPEC-034")
public final class SpokenAnswer {

    /**
     * Teto do que se fala de uma vez: cerca de 25 segundos no ritmo do Piper.
     * Acima disso o ouvinte perde o fio e a tela é o lugar certo.
     */
    static final int MAX_CHARS = 320;

    static final String ON_SCREEN = "O resto está na tela.";
    static final String CODE_ON_SCREEN = "O código está na tela.";

    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```|~~~.*?~~~");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]*\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]*)`");
    private static final Pattern EMPHASIS = Pattern.compile("(\\*{1,3}|_{1,3}|~~)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s*");
    private static final Pattern BULLET = Pattern.compile("(?m)^\\s*(?:[-*+]|\\d+[.)])\\s+");
    private static final Pattern QUOTE = Pattern.compile("(?m)^\\s*>\\s?");
    private static final Pattern RULE = Pattern.compile("(?m)^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$");
    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^\\s*\\|.*\\|\\s*$");
    private static final Pattern LEFTOVER_MARK = Pattern.compile("[*_`#]{1,}");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private SpokenAnswer() {
    }

    /** O que o motor de voz deve dizer sobre esta resposta. */
    public static String of(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        boolean hadCode = FENCED_CODE.matcher(answer).find();
        String plain = strip(answer);
        if (plain.isBlank()) {
            return hadCode ? CODE_ON_SCREEN : "";
        }
        String spoken = shorten(plain);
        boolean cut = spoken.length() < plain.length();
        if (hadCode && cut) {
            return spoken + " " + CODE_ON_SCREEN;
        }
        if (hadCode) {
            return spoken + " " + CODE_ON_SCREEN;
        }
        return cut ? spoken + " " + ON_SCREEN : spoken;
    }

    /** Tira a marcação e deixa só as palavras. */
    static String strip(String markdown) {
        String text = FENCED_CODE.matcher(markdown).replaceAll(" ");
        text = IMAGE.matcher(text).replaceAll(" ");
        text = LINK.matcher(text).replaceAll("$1");
        text = TABLE_ROW.matcher(text).replaceAll(" ");
        text = RULE.matcher(text).replaceAll(" ");
        text = HEADING.matcher(text).replaceAll("");
        text = QUOTE.matcher(text).replaceAll("");
        text = BULLET.matcher(text).replaceAll("");
        text = INLINE_CODE.matcher(text).replaceAll("$1");
        // Ênfase pode estar aninhada (***forte***): repetir até estabilizar.
        for (int i = 0; i < 3; i++) {
            String before = text;
            text = EMPHASIS.matcher(text).replaceAll("$2");
            if (before.equals(text)) {
                break;
            }
        }
        text = LEFTOVER_MARK.matcher(text).replaceAll("");
        return SPACES.matcher(text).replaceAll(" ").strip();
    }

    /**
     * Corta no fim de uma frase dentro do teto. Cortar no meio de uma frase soa
     * como falha do motor de voz, não como resumo.
     */
    static String shorten(String text) {
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        String head = text.substring(0, MAX_CHARS);
        int end = lastSentenceEnd(head);
        if (end > MAX_CHARS / 3) {
            return head.substring(0, end + 1).strip();
        }
        int space = head.lastIndexOf(' ');
        return (space > 0 ? head.substring(0, space) : head).strip() + "…";
    }

    private static int lastSentenceEnd(String text) {
        for (int i = text.length() - 1; i > 0; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == ':') && !abbreviation(text, i)) {
                return i;
            }
        }
        return -1;
    }

    /** "Dr." e "etc." não terminam frase; número com ponto decimal também não. */
    private static boolean abbreviation(String text, int dot) {
        if (dot + 1 < text.length() && Character.isDigit(text.charAt(dot + 1))) {
            return true;
        }
        int start = dot;
        while (start > 0 && Character.isLetter(text.charAt(start - 1))) {
            start--;
        }
        String word = text.substring(start, dot).toLowerCase(Locale.ROOT);
        return word.length() <= 3 && !word.isEmpty() && dot - start <= 3
                && !word.equals("sim") && !word.equals("não");
    }
}
