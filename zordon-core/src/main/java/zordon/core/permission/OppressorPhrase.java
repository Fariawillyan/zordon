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
package zordon.core.permission;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import zordon.api.trace.Spec;

/**
 * O que uma fala pede sobre o OPPRESSOR MODE (SPEC-036 CA-9).
 *
 * <p>A leitura é determinística e não passa pelo modelo. Uma frase que mexe no
 * motor de permissão não pode depender de interpretação: ou o texto casa, ou
 * não casa.
 *
 * <p>Os dois lados não são simétricos, de propósito. {@link #ENTER} só faz a
 * janela pedir a senha — a voz abre o pedido e nunca autoriza sozinha, que é o
 * que o [ADR-0030] exige de uma origem que qualquer pessoa na sala aciona.
 * {@link #EXIT} desliga na hora, sem senha, porque sair é sempre o lado seguro
 * e não faz sentido dificultar o caminho de volta.
 */
@Spec("SPEC-036")
public enum OppressorPhrase {

    /** Pede a senha na tela. */
    ENTER,
    /** Desliga o modo, sem senha. */
    EXIT,
    /** A fala não é sobre o modo: segue como turno normal. */
    NONE;

    /** As grafias do nome que aceitamos, já normalizadas. */
    private static final List<String> NAMES = List.of("modo opressor", "modo oppressor", "oppressor mode");

    /** Palavras que viram a frase para o desligar. */
    private static final List<String> LEAVING =
            List.of("sair", "saia", "desliga", "desligar", "desative", "desativa", "desativar",
                    "encerra", "encerrar", "fecha", "fechar", "para", "parar", "cancela", "cancelar");

    public static OppressorPhrase of(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return NONE;
        }
        String text = normalize(transcript);
        if (NAMES.stream().noneMatch(text::contains)) {
            return NONE;
        }
        // "sair do modo opressor" e "modo opressor" são pedidos opostos e a
        // diferença está só no verbo; sem ele, entrar é a leitura natural.
        return LEAVING.stream().anyMatch(word -> hasWord(text, word)) ? EXIT : ENTER;
    }

    /** Minúsculas, sem acento e sem pontuação: "Modo Opressor!" casa com "modo opressor". */
    private static String normalize(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT);
        return plain.replaceAll("[^a-z0-9]+", " ").strip();
    }

    /** Palavra inteira, para "parar" não casar dentro de "disparar". */
    private static boolean hasWord(String text, String word) {
        return (" " + text + " ").contains(" " + word + " ");
    }
}
