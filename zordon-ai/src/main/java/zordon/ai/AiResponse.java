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

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import zordon.api.TokenUsage;

/** O que o modelo devolveu, com o que custou. */
public record AiResponse(
        List<ContentBlock> content,
        StopReason stopReason,
        TokenUsage usage,
        Money cost,
        String model,
        Duration latency,
        String refusalExplanation,
        boolean usageEstimated) {

    public AiResponse {
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(latency, "latency");
        content = List.copyOf(content);
    }

    public String text() {
        return content.stream()
                .filter(ContentBlock.Text.class::isInstance)
                .map(block -> ((ContentBlock.Text) block).text())
                .reduce("", String::concat);
    }

    public List<ContentBlock.ToolUse> toolCalls() {
        return content.stream().filter(ContentBlock.ToolUse.class::isInstance)
                .map(ContentBlock.ToolUse.class::cast)
                .toList();
    }

    /**
     * Verdadeiro quando o provider não informou o consumo e ele foi estimado pelo
     * tamanho do texto. Estimativa exibida como medida é mentira com cara de número.
     */
    public boolean isUsageEstimated() {
        return usageEstimated;
    }

    /** Presente apenas quando {@link StopReason#REFUSAL}: é o motivo a exibir ao usuário. */
    public Optional<String> refusal() {
        return Optional.ofNullable(refusalExplanation);
    }
}
