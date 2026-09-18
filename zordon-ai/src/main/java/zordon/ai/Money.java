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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Um valor monetário. Nunca {@code double}: custo se soma e arredonda. */
public record Money(BigDecimal amount, String currency) {

    public static final Money ZERO = usd(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    public static Money usd(BigDecimal amount) {
        return new Money(amount, "USD");
    }

    public Money plus(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("moedas diferentes: " + currency + " e " + other.currency);
        }
        return new Money(amount.add(other.amount), currency);
    }

    @Override
    public String toString() {
        return currency + " " + amount.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
