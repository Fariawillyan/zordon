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
package zordon.ai.registry;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/** Um provider como está no {@code config.toml}, já validado. */
public record ProviderConfig(
        String id, ProviderType type, URI baseUrl, SecretRef apiKey, String maxTokensParam) {

    /** Nome atual do parâmetro na OpenAI; alguns servidores compatíveis ainda usam {@code max_tokens}. */
    public static final String DEFAULT_MAX_TOKENS_PARAM = "max_completion_tokens";

    public ProviderConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (type == ProviderType.OPENAI_COMPATIBLE && baseUrl == null) {
            throw new IllegalArgumentException("base_url é obrigatório para type = \"openai-compatible\"");
        }
        if (baseUrl != null && !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("base_url precisa começar com http:// ou https://");
        }
        maxTokensParam = maxTokensParam == null ? DEFAULT_MAX_TOKENS_PARAM : maxTokensParam;
    }

    public Optional<URI> baseUrlIfAny() {
        return Optional.ofNullable(baseUrl);
    }

    public Optional<SecretRef> apiKeyIfAny() {
        return Optional.ofNullable(apiKey);
    }
}
