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
package zordon.ai;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import zordon.api.TokenUsage;

/**
 * Tabela de preços por modelo.
 *
 * <p>Preço é dado de configuração, não constante no código: uma mudança de preço
 * não pode exigir recompilar o Zordon
 * ([Core §1](../../../../docs/specs/core/design.md#1-abstração-de-provider)). Os
 * valores embutidos são o ponto de partida; `~/.zordon/pricing.toml` os substitui,
 * modelo a modelo:
 *
 * <pre>{@code
 * [models."claude-opus-5"]
 * input = 5.00          # US$ por milhão de tokens
 * output = 25.00
 * cache_write = 6.25    # opcional: 1,25 × input
 * cache_read = 0.50     # opcional: 0,10 × input
 * }</pre>
 */
public final class Pricing {

    /** Preço de um modelo, em dólares por milhão de tokens. */
    public record ModelPrice(BigDecimal input, BigDecimal output, BigDecimal cacheWrite, BigDecimal cacheRead) {}

    private static final Logger log = LoggerFactory.getLogger(Pricing.class);
    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");
    private static final BigDecimal CACHE_WRITE_FACTOR = new BigDecimal("1.25");
    private static final BigDecimal CACHE_READ_FACTOR = new BigDecimal("0.10");

    private final Map<String, ModelPrice> prices;

    private Pricing(Map<String, ModelPrice> prices) {
        this.prices = Map.copyOf(prices);
    }

    /** Preços de referência de docs/specs/core/design.md §1. */
    public static Pricing defaults() {
        Map<String, ModelPrice> prices = new HashMap<>();
        prices.put("claude-opus-5", price("5.00", "25.00"));
        prices.put("claude-sonnet-5", price("2.00", "10.00"));
        prices.put("claude-haiku-4-5", price("1.00", "5.00"));
        return new Pricing(prices);
    }

    /** Carrega do arquivo do usuário, caindo para os padrões se ele não existir. */
    public static Pricing load(Path file) {
        if (!Files.isReadable(file)) {
            return defaults();
        }
        try {
            TomlParseResult toml = Toml.parse(file);
            if (toml.hasErrors()) {
                throw new IllegalArgumentException(toml.errors().getFirst().toString());
            }
            Map<String, ModelPrice> prices = new HashMap<>(defaults().prices);
            TomlTable models = toml.getTable("models");
            if (models != null) {
                for (String model : models.keySet()) {
                    prices.put(model, parse(model, models.getTable(List.of(model))));
                }
            }
            return new Pricing(prices);
        } catch (IOException | RuntimeException e) {
            // Preço errado é melhor do que núcleo que não sobe; o aviso é o sinal.
            log.warn("pricing.toml inválido, usando a tabela padrão: {}", e.getMessage());
            return defaults();
        }
    }

    private static ModelPrice parse(String model, TomlTable table) {
        if (table == null) {
            throw new IllegalArgumentException("models." + model + " precisa ser uma tabela");
        }
        BigDecimal input = required(table, model, "input");
        return new ModelPrice(
                input,
                required(table, model, "output"),
                optional(table, "cache_write").orElse(input.multiply(CACHE_WRITE_FACTOR)),
                optional(table, "cache_read").orElse(input.multiply(CACHE_READ_FACTOR)));
    }

    private static BigDecimal required(TomlTable table, String model, String key) {
        return optional(table, key)
                .orElseThrow(() -> new IllegalArgumentException("models." + model + "." + key + " ausente"));
    }

    /** Via texto, não via double: preço se soma e arredonda, e 0,1 em binário não é 0,1. */
    private static java.util.Optional<BigDecimal> optional(TomlTable table, String key) {
        Object value = table.get(List.of(key));
        if (value == null) {
            return java.util.Optional.empty();
        }
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(key + " precisa ser número, veio " + value);
        }
        BigDecimal price = new BigDecimal(value.toString());
        if (price.signum() < 0) {
            throw new IllegalArgumentException(key + " não pode ser negativo");
        }
        return java.util.Optional.of(price);
    }

    /**
     * Custo de um consumo.
     *
     * <p>Modelo desconhecido custa zero e avisa: inventar um preço seria pior, e o
     * aviso é o que revela a tabela desatualizada.
     */
    public Money costOf(String model, TokenUsage usage) {
        ModelPrice price = prices.get(model);
        if (price == null) {
            log.warn("sem preço para o modelo '{}': o custo deste turno não será contabilizado", model);
            return Money.ZERO;
        }
        BigDecimal total = price.input().multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(price.output().multiply(BigDecimal.valueOf(usage.outputTokens())))
                .add(price.cacheWrite().multiply(BigDecimal.valueOf(usage.cacheCreationTokens())))
                .add(price.cacheRead().multiply(BigDecimal.valueOf(usage.cacheReadTokens())))
                .divide(PER_MILLION, 10, RoundingMode.HALF_UP);
        return Money.usd(total);
    }

    public boolean knows(String model) {
        return prices.containsKey(model);
    }

    /**
     * Cache de escrita custa 1,25× a entrada e leitura custa 0,1× — a relação que
     * torna o cache a maior alavanca de custo do sistema.
     */
    private static ModelPrice price(String input, String output) {
        BigDecimal in = new BigDecimal(input);
        return new ModelPrice(
                in, new BigDecimal(output), in.multiply(CACHE_WRITE_FACTOR), in.multiply(CACHE_READ_FACTOR));
    }
}
