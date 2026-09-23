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
package zordon.core.automation;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O {@code when} e a interpolação {@code {{passo.campo}}} dos workflows (SPEC-025).
 * Comparação simples e só: onde precisa de lógica, o passo certo é um agente.
 */
public final class Expressions {

    private static final Pattern WHEN = Pattern.compile(
            "^\\s*(?<ref>[a-z0-9_]+\\.[A-Za-z0-9_]+)\\s*(?:(?<op>==|!=|>=|<=|>|<)\\s*(?<value>.+?))?\\s*$");
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([a-z0-9_]+)\\.([A-Za-z0-9_]+)\\s*}}");

    /** Valida na criação: a referência precisa ser de um passo anterior ou do evento. */
    static void checkWhen(String when, List<String> previous) {
        Matcher matcher = WHEN.matcher(when);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("when inválido: '" + when + "' (use: passo.campo == valor)");
        }
        checkRef(matcher.group("ref").substring(0, matcher.group("ref").indexOf('.')), previous, when);
    }

    static void checkTemplate(String text, List<String> previous) {
        Matcher matcher = TEMPLATE.matcher(text);
        while (matcher.find()) {
            checkRef(matcher.group(1), previous, text);
        }
    }

    private static void checkRef(String step, List<String> previous, String where) {
        if (!"event".equals(step) && !previous.contains(step)) {
            throw new IllegalArgumentException("'" + where + "' se refere a '" + step
                    + "', que não é um passo anterior nem o evento");
        }
    }

    /** Avalia com os resultados até aqui. Referência ausente é falso. */
    public static boolean when(String when, Map<String, Map<String, Object>> context) {
        if (when == null || when.isBlank()) {
            return true;
        }
        Matcher matcher = WHEN.matcher(when);
        if (!matcher.matches()) {
            return false;
        }
        Object left = resolve(matcher.group("ref"), context);
        if (left == null) {
            return false;
        }
        String op = matcher.group("op");
        if (op == null) {
            return truthy(String.valueOf(left));
        }
        return compare(String.valueOf(left), op, unquote(matcher.group("value").strip()));
    }

    /** Sem operador, a condição é a presença de um valor que não seja "vazio". */
    private static boolean truthy(String value) {
        return !"false".equals(value) && !"0".equals(value) && !value.isEmpty();
    }

    /** Ordem só vale entre números; entre textos, apenas igualdade. */
    private static boolean compare(String leftText, String op, String right) {
        Double leftNumber = number(leftText);
        Double rightNumber = number(right);
        if (leftNumber != null && rightNumber != null) {
            return compareNumbers(Double.compare(leftNumber, rightNumber), op);
        }
        return switch (op) {
            case "==" -> leftText.equals(right);
            case "!=" -> !leftText.equals(right);
            default -> false;
        };
    }

    private static boolean compareNumbers(int compared, String op) {
        return switch (op) {
            case "==" -> compared == 0;
            case "!=" -> compared != 0;
            case ">" -> compared > 0;
            case "<" -> compared < 0;
            case ">=" -> compared >= 0;
            default -> compared <= 0;
        };
    }

    /** Troca {@code {{passo.campo}}} pelo valor; o que não existe vira vazio. */
    public static String render(String text, Map<String, Map<String, Object>> context) {
        if (text == null) {
            return null;
        }
        Matcher matcher = TEMPLATE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = resolve(matcher.group(1) + "." + matcher.group(2), context);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Os argumentos com os textos interpolados. */
    public static Map<String, Object> render(Map<String, Object> args, Map<String, Map<String, Object>> context) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        args.forEach((key, value) -> out.put(key, renderValue(value, context)));
        return out;
    }

    private static Object renderValue(Object value, Map<String, Map<String, Object>> context) {
        if (value instanceof String text) {
            return render(text, context);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), renderValue(item, context)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> renderValue(item, context)).toList();
        }
        return value;
    }

    private static Object resolve(String ref, Map<String, Map<String, Object>> context) {
        int dot = ref.indexOf('.');
        Map<String, Object> step = context.get(ref.substring(0, dot));
        return step == null ? null : step.get(ref.substring(dot + 1));
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"")
                || text.startsWith("'") && text.endsWith("'"))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static Double number(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Expressions() {}
}
