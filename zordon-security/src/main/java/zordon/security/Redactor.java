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
package zordon.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import zordon.api.trace.Spec;

/**
 * Mascara segredos conhecidos antes de qualquer registro (docs/security/model.md
 * §5, SPEC-014 CA-3). Um segredo aparece só com o prefixo e os 3 últimos
 * caracteres: {@code sk-proj-****************92F}. Nunca completo.
 */
@Spec("SPEC-014")
public final class Redactor {

    private static final List<Pattern> TOKENS = List.of(
            Pattern.compile("sk-(?:proj-|ant-[a-z0-9]{2,6}-)?[A-Za-z0-9_\\-]{16,}"),
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{30,}"),
            Pattern.compile("github_pat_[A-Za-z0-9_]{30,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("xox[abprs]-[A-Za-z0-9\\-]{10,}"),
            Pattern.compile("AIza[0-9A-Za-z_\\-]{30,}"));
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key)(\\s*[=:]\\s*)([^\\s,;\"']{4,})");
    private static final Pattern SENSITIVE_KEY = Pattern.compile("(?i)password|passwd|secret|token|api[_-]?key");

    /** O texto com cada segredo reconhecido mascarado. */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = PRIVATE_KEY.matcher(text).replaceAll("[chave privada omitida]");
        for (Pattern token : TOKENS) {
            out = token.matcher(out).replaceAll(match -> Matcher.quoteReplacement(mask(match.group())));
        }
        return ASSIGNMENT.matcher(out).replaceAll(match -> Matcher.quoteReplacement(
                match.group(1) + match.group(2) + mask(match.group(3))));
    }

    /** Argumentos redigidos, em profundidade; chave com nome de segredo tem o valor mascarado inteiro. */
    public Map<String, Object> redact(Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        args.forEach((key, value) -> out.put(key,
                SENSITIVE_KEY.matcher(key).find() && value instanceof String text ? mask(text) : redactValue(value)));
        return out;
    }

    public boolean containsSecret(String text) {
        return text != null && !redact(text).equals(text);
    }

    public boolean containsSecret(Map<String, Object> args) {
        return !redact(args).equals(args);
    }

    @SuppressWarnings("unchecked")
    private Object redactValue(Object value) {
        if (value instanceof String text) {
            return redact(text);
        }
        if (value instanceof Map<?, ?> map) {
            return redact((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::redactValue).toList();
        }
        return value;
    }

    /** Prefixo (letras e separadores iniciais) + asteriscos + 3 últimos caracteres. */
    static String mask(String secret) {
        if (secret.length() < 8) {
            return "****";
        }
        Matcher prefix = Pattern.compile("^(?:[A-Za-z]+[-_]){1,2}").matcher(secret);
        int keep = prefix.find() && prefix.end() <= secret.length() - 6 ? prefix.end() : 0;
        int stars = Math.max(4, secret.length() - keep - 3);
        return secret.substring(0, keep) + "*".repeat(stars) + secret.substring(secret.length() - 3);
    }
}
